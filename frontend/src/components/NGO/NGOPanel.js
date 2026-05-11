import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  Grid,
  Paper,
  Typography,
  ToggleButtonGroup,
  ToggleButton,
  Card,
  CardContent,
  CardActions,
  Chip,
  Stack,
  Button,
  Table,
  TableHead,
  TableRow,
  TableCell,
  TableBody,
  Box,
  Divider,
  Select,
  MenuItem,
  FormControl,
  InputLabel,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  TextField,
  Input,
} from '@mui/material';
import { VolunteerActivism, Agriculture, Pets } from '@mui/icons-material';
import { QRCodeCanvas } from 'qrcode.react';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import { useSnackbar } from 'notistack';
import SockJS from 'sockjs-client';
import { Client } from '@stomp/stompjs';
import { MapContainer, TileLayer, Marker, Popup, Polyline, useMap } from 'react-leaflet';
import api from '../../utils/api';
import { WS_SOCKJS_URL, resolveServerUrl } from '../../utils/appConfig';
import { bearingDeg } from '../../utils/geo';
import PanelLayout from '../Layout/PanelLayout';
import StatCard from '../Common/StatCard';
import { useAuth } from '../../context/AuthContext';
import { resolvePickupLocation, resolveRequestDestination } from '../../utils/location';
import L from 'leaflet';
import { formatDistance, formatDuration, getRoute } from '../../utils/routing';

dayjs.extend(relativeTime);

const MapAutoCenter = ({ center }) => {
  const map = useMap();
  useEffect(() => {
    if (center?.[0] && center?.[1]) {
      map.setView(center, map.getZoom(), { animate: true });
    }
  }, [center, map]);
  return null;
};

const donationFilters = [
  { value: 'HUMAN', label: 'Human', icon: <VolunteerActivism fontSize="small" /> },
  { value: 'DOG', label: 'Dogs', icon: <Pets fontSize="small" /> },
  { value: 'COMPOST', label: 'Compost', icon: <Agriculture fontSize="small" /> },
];

const NGOPanel = ({ darkMode, setDarkMode }) => {
  const { user } = useAuth();
  const [availableDonations, setAvailableDonations] = useState([]);
  const [smartMatches, setSmartMatches] = useState([]);
  const [myRequests, setMyRequests] = useState([]);
  const [pendingRequests, setPendingRequests] = useState([]);
  const [donationSelection, setDonationSelection] = useState({});
  const [selectedType, setSelectedType] = useState('HUMAN');
  const [selectedTrackingRequest, setSelectedTrackingRequest] = useState(null);
  const [tracking, setTracking] = useState(null);
  const [animatedPosition, setAnimatedPosition] = useState(null);
  const [pickupLocation, setPickupLocation] = useState(null);
  const [destinationLocation, setDestinationLocation] = useState(null);
  const [routePath, setRoutePath] = useState([]);
  const [etaSeconds, setEtaSeconds] = useState(null);
  const [distanceMeters, setDistanceMeters] = useState(null);
  const wsClientRef = useRef(null);
  const trackingSubRef = useRef(null);
  const pendingSubscriptionRequestId = useRef(null);
  const [wsClient, setWsClient] = useState(null);
  const [qrTokenInput, setQrTokenInput] = useState('');
  const animationFrameRef = useRef(null);
  const { enqueueSnackbar } = useSnackbar();
  const [riderBearing, setRiderBearing] = useState(0);
  const routeDebounceRef = useRef(null);
  const [compostDialog, setCompostDialog] = useState({
    open: false,
    request: null,
    note: '',
    imageFile: null,
    imagePreview: null,
    uploading: false,
  });

  const bikeIcon = useMemo(
    () =>
      L.divIcon({
        html: `<div style="font-size:28px;line-height:28px;transform:rotate(${riderBearing}deg);transition:transform 0.45s ease-out;">🏍️</div>`,
        className: '',
        iconSize: [28, 28],
        iconAnchor: [14, 14],
      }),
    [riderBearing],
  );

  useEffect(() => {
    if (user && user.userId && user.token) {
      loadAvailableDonations();
      loadMyRequests();
      loadPendingRequests();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedType, user]);

  useEffect(() => {
    if (user && user.userId && user.token) {
      loadMyRequests();
      loadPendingRequests();
      const stomp = connectWebSocket();
      return () => stomp?.deactivate();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user]);

  const loadAvailableDonations = async () => {
    if (!user || !user.token) return;
    try {
      const [matchesRes, donationsRes] = await Promise.all([
        api.get(`/donations/smart-match?ngoId=${user.userId}`).catch(() => ({ data: [] })),
        api.get(`/donations/available/${selectedType}/prioritized`).catch(async () => {
          const fallback = await api.get(`/donations/available/${selectedType}`);
          return { data: (fallback.data || []).map((d) => ({ donation: d, urgencyScore: 0, reasons: [] })) };
        }),
      ]);
      const filteredMatches = (matchesRes.data || []).filter((m) => m.donation?.donationType === selectedType);
      setSmartMatches(filteredMatches);
      const prioritized = (donationsRes.data || []).map((entry) => ({
        ...(entry.donation || {}),
        urgencyScore: entry.urgencyScore ?? 0,
        urgencyReasons: entry.reasons || [],
      }));
      setAvailableDonations(prioritized);
    } catch (err) {
      // Don't show error for 401/403 - might be user not approved yet
      if (err.response?.status !== 401 && err.response?.status !== 403) {
        enqueueSnackbar('Unable to fetch donations at the moment.', { variant: 'error' });
      }
      setAvailableDonations([]);
    }
  };

  const loadMyRequests = async () => {
    if (!user || !user.token || !user.userId) return;
    try {
      const response = await api.get(`/requests/requester/${user.userId}`);
      setMyRequests(response.data || []);
    } catch (err) {
      // Don't show error for 401/403 - might be user not approved yet
      if (err.response?.status !== 401 && err.response?.status !== 403) {
        enqueueSnackbar('Unable to load your request history.', { variant: 'error' });
      }
      setMyRequests([]);
    }
  };

  const loadPendingRequests = async () => {
    if (!user || !user.token) return;
    try {
      const response = await api.get('/requests/pending');
      setPendingRequests(response.data || []);
    } catch (err) {
      enqueueSnackbar('Unable to load pending requests.', { variant: 'error' });
      setPendingRequests([]);
    }
  };

  const handleAcceptRequest = async (requestId) => {
    try {
      await api.put(`/requests/${requestId}/accept`);
      enqueueSnackbar('Request accepted successfully.', { variant: 'success' });
      loadPendingRequests();
      loadMyRequests();
    } catch (err) {
      enqueueSnackbar('Unable to accept request.', { variant: 'error' });
    }
  };

  const handleAssignDonation = async (requestId, donationId) => {
    if (!donationId) {
      enqueueSnackbar('Select a donation before assigning.', { variant: 'warning' });
      return;
    }
    try {
      await api.put(`/requests/${requestId}/assign-donation?donationId=${donationId}`);
      enqueueSnackbar('Donation assigned to request.', { variant: 'success' });
      loadAvailableDonations();
      loadPendingRequests();
      loadMyRequests();
    } catch (err) {
      enqueueSnackbar('Unable to assign donation.', { variant: 'error' });
    }
  };

  const handleSelectDonation = (requestId, donationId) => {
    setDonationSelection((prev) => ({ ...prev, [requestId]: Number(donationId) }));
  };

  const connectWebSocket = () => {
    const stomp = new Client({
      reconnectDelay: 5000,
      webSocketFactory: () => new SockJS(WS_SOCKJS_URL),
    });
    stomp.onConnect = () => {
      wsClientRef.current = stomp;
      setWsClient(stomp);
      if (pendingSubscriptionRequestId.current) {
        const requestId = pendingSubscriptionRequestId.current;
        pendingSubscriptionRequestId.current = null;
        subscribeToRequestTracking(requestId);
      }
    };
    stomp.activate();
    return stomp;
  };

  const subscribeToRequestTracking = (requestId) => {
    if (!wsClientRef.current) {
      pendingSubscriptionRequestId.current = requestId;
      return;
    }
    if (trackingSubRef.current) {
      trackingSubRef.current.unsubscribe();
      trackingSubRef.current = null;
    }
    trackingSubRef.current = wsClientRef.current.subscribe(`/topic/tracking/${requestId}`, (message) => {
      const data = JSON.parse(message.body);
      setTracking(data);
    });
  };

  const handleTrackRequest = async (request) => {
    setSelectedTrackingRequest(request);
    setTracking(null);
    setAnimatedPosition(null);
    setPickupLocation(null);
    setDestinationLocation(null);
    setRoutePath([]);
    setEtaSeconds(null);
    setDistanceMeters(null);
    subscribeToRequestTracking(request.id);
    const [pickup, destination] = await Promise.all([
      resolvePickupLocation(request),
      resolveRequestDestination(request),
    ]);
    setPickupLocation(pickup);
    setDestinationLocation(destination);
    try {
      const res = await api.get(`/tracking/request/${request.id}/latest`);
      setTracking(res.data);
    } catch {
      // Ignore when no location has been pushed yet
    }
  };

  useEffect(() => {
    if (!tracking?.latitude || !tracking?.longitude) return;
    const target = [tracking.latitude, tracking.longitude];
    if (!animatedPosition) {
      setAnimatedPosition(target);
      return;
    }
    setRiderBearing(bearingDeg(animatedPosition[0], animatedPosition[1], target[0], target[1]));
    if (animationFrameRef.current) {
      cancelAnimationFrame(animationFrameRef.current);
    }

    const start = performance.now();
    const durationMs = 900;
    const from = animatedPosition;
    const tick = (now) => {
      const progress = Math.min((now - start) / durationMs, 1);
      const lat = from[0] + (target[0] - from[0]) * progress;
      const lng = from[1] + (target[1] - from[1]) * progress;
      setAnimatedPosition([lat, lng]);
      if (progress < 1) {
        animationFrameRef.current = requestAnimationFrame(tick);
      }
    };
    animationFrameRef.current = requestAnimationFrame(tick);
    return () => {
      if (animationFrameRef.current) {
        cancelAnimationFrame(animationFrameRef.current);
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tracking]);

  useEffect(() => {
    if (routeDebounceRef.current) {
      clearTimeout(routeDebounceRef.current);
    }
    routeDebounceRef.current = setTimeout(async () => {
      if (!animatedPosition || !destinationLocation) {
        setRoutePath([]);
        setEtaSeconds(null);
        setDistanceMeters(null);
        return;
      }
      try {
        const route = await getRoute(animatedPosition, destinationLocation);
        if (route) {
          setRoutePath(route.points);
          setEtaSeconds(route.durationSeconds);
          setDistanceMeters(route.distanceMeters);
        }
      } catch {
        setRoutePath([]);
      }
    }, 350);
    return () => {
      if (routeDebounceRef.current) {
        clearTimeout(routeDebounceRef.current);
      }
    };
  }, [animatedPosition, destinationLocation]);

  const handleRequestDonation = async (donationId) => {
    try {
      await api.post(
        `/requests?donationId=${donationId}&requesterId=${user.userId}&requesterType=NGO`
      );
      enqueueSnackbar('Request submitted. A volunteer will be assigned shortly.', { variant: 'success' });
      loadAvailableDonations();
      loadMyRequests();
    } catch (err) {
      enqueueSnackbar('Request failed. This donation may already be assigned.', { variant: 'error' });
    }
  };

  const handleNgoDonation = async (amount) => {
    try {
      const res = await api.post('/payments/create-session', {
        userId: user?.userId || null,
        ngoId: user?.userId || null,
        amount,
      });
      if (res.data?.checkoutUrl) {
        window.open(res.data.checkoutUrl, '_blank');
      } else {
        enqueueSnackbar('Checkout URL missing from payment session.', { variant: 'warning' });
      }
    } catch (error) {
      enqueueSnackbar(error.response?.data || error.response?.data?.message || 'Unable to start payment.', { variant: 'error' });
    }
  };

  const verifyQr = async (requestId, stage) => {
    try {
      await api.post(`/requests/${requestId}/verify-qr`, null, {
        params: { token: qrTokenInput.trim(), stage },
      });
      enqueueSnackbar(`${stage} QR verified.`, { variant: 'success' });
      setQrTokenInput('');
      loadMyRequests();
    } catch (error) {
      enqueueSnackbar(error.response?.data?.message || 'QR verification failed.', { variant: 'error' });
    }
  };

  const handleCompostFile = (e) => {
    const file = e.target.files?.[0];
    if (!file || !file.type.startsWith('image/')) {
      enqueueSnackbar('Choose an image for compost proof.', { variant: 'warning' });
      return;
    }
    const reader = new FileReader();
    reader.onloadend = () =>
      setCompostDialog((p) => ({ ...p, imageFile: file, imagePreview: reader.result }));
    reader.readAsDataURL(file);
  };

  const submitCompostProof = async () => {
    const req = compostDialog.request;
    if (!req || !compostDialog.imageFile) {
      enqueueSnackbar('Photo is required.', { variant: 'warning' });
      return;
    }
    setCompostDialog((p) => ({ ...p, uploading: true }));
    try {
      const fd = new FormData();
      fd.append('file', compostDialog.imageFile);
      const up = await api.post('/files/upload', fd);
      const proofUrl = resolveServerUrl(up.data?.url);
      await api.put(`/requests/${req.id}/status`, null, {
        params: {
          status: 'COMPOSTED',
          compostProofUrl: proofUrl,
          compostProofNote: compostDialog.note || undefined,
        },
      });
      enqueueSnackbar('Compost processing recorded.', { variant: 'success' });
      setCompostDialog({
        open: false,
        request: null,
        note: '',
        imageFile: null,
        imagePreview: null,
        uploading: false,
      });
      loadMyRequests();
      loadAvailableDonations();
    } catch {
      enqueueSnackbar('Could not save compost proof.', { variant: 'error' });
    } finally {
      setCompostDialog((p) => ({ ...p, uploading: false }));
    }
  };

  const stats = useMemo(
    () => [
      { label: 'Open requests', value: myRequests.filter((r) => r.status !== 'DELIVERED').length },
      { label: 'Delivered meals', value: myRequests.filter((r) => r.status === 'DELIVERED').length },
      { label: 'Available donations', value: availableDonations.length },
    ],
    [availableDonations.length, myRequests]
  );

  const statusColor = (status) => {
    switch (status) {
      case 'DELIVERED':
        return 'success';
      case 'PENDING':
        return 'warning';
      default:
        return 'info';
    }
  };

  return (
    <PanelLayout
      title="NGO Operations Hub"
      subtitle="Match surplus meals with shelters, missions, and feeding drives."
      darkMode={darkMode}
      setDarkMode={setDarkMode}
    >
      <Grid container spacing={3} sx={{ mb: 3 }}>
        <Grid item xs={12} md={4}>
          <StatCard label="Open requests" value={stats[0].value} icon={<VolunteerActivism />} />
        </Grid>
        <Grid item xs={12} md={4}>
          <StatCard label="Delivered meals" value={stats[1].value} icon={<Pets />} color="secondary" />
        </Grid>
        <Grid item xs={12} md={4}>
          <StatCard label="Available donations" value={stats[2].value} icon={<Agriculture />} />
        </Grid>
      </Grid>

      <Paper elevation={0} sx={{ p: 3, mb: 3 }}>
        <Typography variant="h6">Support NGO Fund</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
          Accept direct support through Stripe Checkout.
        </Typography>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
          {[50, 100, 500].map((amount) => (
            <Button key={amount} variant="outlined" onClick={() => handleNgoDonation(amount)}>
              Donate ₹{amount}
            </Button>
          ))}
        </Stack>
      </Paper>

      <Paper elevation={0} sx={{ p: 3, mb: 3 }}>
        <Typography variant="h6" sx={{ mb: 2 }}>
          Pending Requests
        </Typography>
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>ID</TableCell>
              <TableCell>Requester</TableCell>
              <TableCell>Phone</TableCell>
              <TableCell>Status</TableCell>
              <TableCell>Food Type</TableCell>
              <TableCell>Actions</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {pendingRequests.length === 0 ? (
              <TableRow>
                <TableCell colSpan={6} align="center">
                  No pending requests found.
                </TableCell>
              </TableRow>
            ) : (
              pendingRequests.map((request) => (
                <TableRow key={request.id}>
                  <TableCell>{request.id}</TableCell>
                  <TableCell>{request.requesterName || request.requesterType}</TableCell>
                  <TableCell>{request.phoneNumber || request.phone || '—'}</TableCell>
                  <TableCell>
                    <Chip label={request.status} color={statusColor(request.status)} size="small" />
                  </TableCell>
                  <TableCell>{request.foodType || 'N/A'}</TableCell>
                  <TableCell>
                    {request.status === 'PENDING' && (
                      <Button size="small" variant="contained" onClick={() => handleAcceptRequest(request.id)}>
                        Accept
                      </Button>
                    )}
                    {request.status === 'ACCEPTED' && (
                      <Stack direction="row" spacing={1} alignItems="center">
                        <FormControl size="small" sx={{ minWidth: 160 }}>
                          <InputLabel id={`assign-label-${request.id}`}>Donation</InputLabel>
                          <Select
                            labelId={`assign-label-${request.id}`}
                            value={donationSelection[request.id] || ''}
                            label="Donation"
                            onChange={(event) => handleSelectDonation(request.id, event.target.value)}
                          >
                            <MenuItem value="">Choose</MenuItem>
                            {availableDonations.map((donation) => (
                              <MenuItem key={donation.id} value={donation.id}>
                                #{donation.id} — {donation.foodType}
                              </MenuItem>
                            ))}
                          </Select>
                        </FormControl>
                        <Button
                          size="small"
                          variant="contained"
                          onClick={() => handleAssignDonation(request.id, donationSelection[request.id])}
                        >
                          Assign
                        </Button>
                      </Stack>
                    )}
                  </TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </Paper>

      <Grid container spacing={3}>
        <Grid item xs={12} md={7}>
          <Paper elevation={0} sx={{ p: 3 }}>
            <Stack direction="row" justifyContent="space-between" alignItems="center" mb={2}>
              <div>
                <Typography variant="h6">Nearby donations</Typography>
                <Typography variant="body2" color="text.secondary">
                  Choose the food quality that matches your beneficiaries.
                </Typography>
              </div>
              <ToggleButtonGroup
                size="small"
                value={selectedType}
                exclusive
                onChange={(_, value) => value && setSelectedType(value)}
              >
                {donationFilters.map((filter) => (
                  <ToggleButton key={filter.value} value={filter.value}>
                    {filter.icon}
                    <Typography variant="caption" ml={1}>
                      {filter.label}
                    </Typography>
                  </ToggleButton>
                ))}
              </ToggleButtonGroup>
            </Stack>

            <Grid container spacing={2}>
              {availableDonations.length === 0 && (
                <Grid item xs={12}>
                  <Typography variant="body2" color="text.secondary">
                    No donations of this type right now. Try another filter or refresh shortly.
                  </Typography>
                </Grid>
              )}
              {(smartMatches.length > 0 ? smartMatches.map((m) => m.donation) : availableDonations).map((donation) => (
                <Grid item xs={12} key={donation.id}>
                  <Card variant="outlined">
                    <CardContent>
                      <Stack direction="row" justifyContent="space-between">
                        <div>
                          <Typography variant="h6">{donation.foodName}</Typography>
                          <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
                            {donation.description || 'No extra details provided.'}
                          </Typography>
                          <Stack direction="row" spacing={2} flexWrap="wrap">
                            <Chip label={`${donation.quantity} meals`} size="small" />
                            <Chip
                              label={`Expires ${dayjs(donation.expiryDate).fromNow()}`}
                              size="small"
                              color="warning"
                            />
                            {smartMatches.find((m) => m.donation?.id === donation.id)?.riskLevel && (
                              <Chip
                                label={`Risk ${smartMatches.find((m) => m.donation?.id === donation.id)?.riskLevel}`}
                                size="small"
                                color="error"
                              />
                            )}
                            {typeof donation.urgencyScore === 'number' && (
                              <Chip
                                label={`Urgency ${donation.urgencyScore}`}
                                size="small"
                                color={donation.urgencyScore >= 60 ? 'error' : donation.urgencyScore >= 35 ? 'warning' : 'default'}
                              />
                            )}
                          </Stack>
                        </div>
                        <Typography variant="body2" color="text.secondary">
                          {donation.address}
                        </Typography>
                      </Stack>
                    </CardContent>
                    <CardActions sx={{ justifyContent: 'flex-end', px: 3, pb: 2 }}>
                      <Button onClick={() => handleRequestDonation(donation.id)} variant="contained">
                        Request pickup
                      </Button>
                    </CardActions>
                  </Card>
                </Grid>
              ))}
            </Grid>
          </Paper>
        </Grid>

        <Grid item xs={12} md={5}>
          <Paper elevation={0} sx={{ p: 3, height: '100%' }}>
            <Stack direction="row" justifyContent="space-between" alignItems="center" mb={2}>
              <div>
                <Typography variant="h6">My request timeline</Typography>
                <Typography variant="body2" color="text.secondary">
                  Track assignment status and volunteer partners.
                </Typography>
              </div>
              <Button size="small" onClick={loadMyRequests}>
                Refresh
              </Button>
            </Stack>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Meal</TableCell>
                  <TableCell>Status</TableCell>
                  <TableCell>Volunteer</TableCell>
                  <TableCell align="right">Track / QR</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {myRequests.map((request) => (
                  <TableRow key={request.id}>
                    <TableCell>
                      <Typography variant="subtitle2">{request.donation?.foodName}</Typography>
                      <Typography variant="caption" color="text.secondary">
                        Placed {dayjs(request.createdAt).format('MMM DD, HH:mm')}
                      </Typography>
                    </TableCell>
                    <TableCell>
                      <Chip
                        label={request.status}
                        size="small"
                        color={statusColor(request.status)}
                      />
                    </TableCell>
                    <TableCell>
                      {request.assignedVolunteer?.fullName || (
                        <Chip label="Awaiting volunteer" size="small" />
                      )}
                    </TableCell>
                    <TableCell align="right">
                      <Stack direction="column" spacing={0.5} alignItems="flex-end">
                        {request.assignedVolunteer && (
                          <Button size="small" onClick={() => handleTrackRequest(request)}>
                            {request.status === 'DELIVERED' ? 'Proof' : 'Live'}
                          </Button>
                        )}
                        {request.qrToken && (
                          <QRCodeCanvas value={request.qrToken} size={58} />
                        )}
                        <Button size="small" variant="outlined" onClick={() => verifyQr(request.id, 'DELIVERY')}>
                          Verify Delivery QR
                        </Button>
                        {request.donation?.donationType === 'COMPOST' && request.status === 'DELIVERED' && (
                          <Button
                            size="small"
                            color="secondary"
                            variant="contained"
                            onClick={() =>
                              setCompostDialog({
                                open: true,
                                request,
                                note: '',
                                imageFile: null,
                                imagePreview: null,
                                uploading: false,
                              })
                            }
                          >
                            Compost proof
                          </Button>
                        )}
                      </Stack>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            <Divider sx={{ my: 2 }} />
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
              <input
                value={qrTokenInput}
                onChange={(e) => setQrTokenInput(e.target.value)}
                placeholder="Paste scanned QR token"
                style={{ flex: 1, padding: 8, borderRadius: 6, border: '1px solid #bbb' }}
              />
            </Stack>
          </Paper>
        </Grid>
      </Grid>

      {selectedTrackingRequest && (
        <Paper elevation={0} sx={{ p: 3, mt: 3 }}>
          <Typography variant="h6" mb={2}>
            Live volunteer tracking - {selectedTrackingRequest.donation?.foodName}
          </Typography>
          <Stack direction="row" spacing={2} mb={2} flexWrap="wrap">
            <Chip color="primary" label={`ETA ${formatDuration(etaSeconds)}`} />
            <Chip color="secondary" label={`Distance ${formatDistance(distanceMeters)}`} />
          </Stack>
          <MapContainer
            center={
              animatedPosition
                ? animatedPosition
                : destinationLocation || pickupLocation || [20.5937, 78.9629]
            }
            zoom={13}
            style={{ width: '100%', height: 320, borderRadius: 12 }}
          >
            <TileLayer
              attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
              url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
            />
            {animatedPosition && (
              <Marker position={animatedPosition} icon={bikeIcon}>
                <Popup>Volunteer live location</Popup>
              </Marker>
            )}
            {pickupLocation && (
              <Marker position={pickupLocation}>
                <Popup>Pickup point</Popup>
              </Marker>
            )}
            {destinationLocation && (
              <Marker position={destinationLocation}>
                <Popup>Destination</Popup>
              </Marker>
            )}
            {routePath.length > 1 ? (
              <Polyline
                positions={routePath}
                pathOptions={{ color: '#2e7d32', weight: 5 }}
              />
            ) : null}
            {animatedPosition && destinationLocation && routePath.length < 2 && (
              <Polyline
                positions={[
                  animatedPosition,
                  destinationLocation,
                ]}
                pathOptions={{ color: '#2e7d32', weight: 4 }}
              />
            )}
            <MapAutoCenter center={animatedPosition || null} />
          </MapContainer>

          {selectedTrackingRequest.status === 'DELIVERED' && selectedTrackingRequest.deliveryProofUrl && (
            <Stack spacing={1} mt={2}>
              <Typography variant="subtitle2">Delivery proof</Typography>
              <Box
                component="img"
                src={selectedTrackingRequest.deliveryProofUrl}
                alt="Delivery proof"
                sx={{ width: 260, maxWidth: '100%', borderRadius: 1 }}
              />
              {selectedTrackingRequest.deliveryProofNote && (
                <Typography variant="body2" color="text.secondary">
                  {selectedTrackingRequest.deliveryProofNote}
                </Typography>
              )}
            </Stack>
          )}
        </Paper>
      )}

      <Dialog open={compostDialog.open} onClose={() => !compostDialog.uploading && setCompostDialog((p) => ({ ...p, open: false }))} maxWidth="sm" fullWidth>
        <DialogTitle>Verify compost processing</DialogTitle>
        <DialogContent>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            Upload a photo from your composting facility. This closes the loop for regulators and impact metrics.
          </Typography>
          <Input type="file" inputProps={{ accept: 'image/*' }} onChange={handleCompostFile} sx={{ mb: 2 }} />
          {compostDialog.imagePreview && (
            <Box component="img" src={compostDialog.imagePreview} alt="" sx={{ width: '100%', maxHeight: 200, objectFit: 'cover', borderRadius: 1, mb: 2 }} />
          )}
          <TextField
            fullWidth
            label="Note (optional)"
            value={compostDialog.note}
            onChange={(e) => setCompostDialog((p) => ({ ...p, note: e.target.value }))}
            multiline
            minRows={2}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setCompostDialog((p) => ({ ...p, open: false }))} disabled={compostDialog.uploading}>
            Cancel
          </Button>
          <Button variant="contained" onClick={submitCompostProof} disabled={compostDialog.uploading}>
            {compostDialog.uploading ? 'Saving…' : 'Submit proof'}
          </Button>
        </DialogActions>
      </Dialog>
    </PanelLayout>
  );
};

export default NGOPanel;

