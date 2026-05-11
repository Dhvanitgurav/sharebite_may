import React, { useState, useEffect, useMemo, useRef } from 'react';
import {
  Grid,
  Paper,
  Typography,
  Chip,
  Table,
  TableHead,
  TableRow,
  TableCell,
  TableBody,
  Button,
  Stack,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  TextField,
  Box,
  Input,
} from '@mui/material';
import { EmojiEvents, LocalShipping, Navigation } from '@mui/icons-material';
import { MapContainer, TileLayer, Marker, Polyline, Popup, useMap } from 'react-leaflet';
import { useSnackbar } from 'notistack';
import SockJS from 'sockjs-client';
import { Client } from '@stomp/stompjs';
import PanelLayout from '../Layout/PanelLayout';
import StatCard from '../Common/StatCard';
import api from '../../utils/api';
import { WS_SOCKJS_URL, resolveServerUrl } from '../../utils/appConfig';
import { useAuth } from '../../context/AuthContext';
import { resolvePickupLocation, resolveRequestDestination } from '../../utils/location';
import L from 'leaflet';
import { formatDistance, formatDuration, getRoute } from '../../utils/routing';
import { QRCodeCanvas } from 'qrcode.react';
import { bearingDeg } from '../../utils/geo';

const mapContainerStyle = { width: '100%', height: 360, borderRadius: 12 };

const MapAutoCenter = ({ center }) => {
  const map = useMap();
  useEffect(() => {
    if (center?.[0] && center?.[1]) {
      map.setView(center, map.getZoom(), { animate: true });
    }
  }, [center, map]);
  return null;
};

const VolunteerPanel = ({ darkMode, setDarkMode }) => {
  const { user } = useAuth();
  const { enqueueSnackbar } = useSnackbar();
  const [requests, setRequests] = useState([]);
  const [myRequests, setMyRequests] = useState([]);
  const [selectedRequest, setSelectedRequest] = useState(null);
  const [tracking, setTracking] = useState(null);
  const [animatedPosition, setAnimatedPosition] = useState(null);
  const [pickupLocation, setPickupLocation] = useState(null);
  const [destinationLocation, setDestinationLocation] = useState(null);
  const [routePath, setRoutePath] = useState([]);
  const [etaSeconds, setEtaSeconds] = useState(null);
  const [distanceMeters, setDistanceMeters] = useState(null);
  const [points, setPoints] = useState(null);
  const clientRef = useRef(null);
  const trackingSubRef = useRef(null);
  const pendingSubscriptionRequestId = useRef(null);
  const geoWatchRef = useRef(null);
  const [client, setClient] = useState(null);
  const animationFrameRef = useRef(null);
  const [deliveryDialog, setDeliveryDialog] = useState({
    open: false,
    request: null,
    note: '',
    imageFile: null,
    imagePreview: null,
    uploading: false,
  });
  const [badges, setBadges] = useState([]);
  const [qrVerificationToken, setQrVerificationToken] = useState('');
  const [riderBearing, setRiderBearing] = useState(0);
  const routeDebounceRef = useRef(null);
  const lastTrackingErrorRef = useRef(0);

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
      loadRequests();
      loadMyRequests();
      loadPoints();
      loadBadges();
      const stomp = connectWebSocket();
      return () => {
        stomp?.deactivate();
      };
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user]);

  const connectWebSocket = () => {
    const stomp = new Client({
      reconnectDelay: 5000,
      webSocketFactory: () => new SockJS(WS_SOCKJS_URL),
    });
    stomp.onConnect = () => {
      clientRef.current = stomp;
      setClient(stomp);
      if (pendingSubscriptionRequestId.current) {
        const requestId = pendingSubscriptionRequestId.current;
        pendingSubscriptionRequestId.current = null;
        subscribeToRequest(requestId);
      }
    };
    stomp.activate();
    return stomp;
  };

  const subscribeToRequest = (requestId) => {
    if (!clientRef.current) {
      pendingSubscriptionRequestId.current = requestId;
      return;
    }
    if (trackingSubRef.current) {
      trackingSubRef.current.unsubscribe();
      trackingSubRef.current = null;
    }
    trackingSubRef.current = clientRef.current.subscribe(`/topic/tracking/${requestId}`, (message) => {
      const data = JSON.parse(message.body);
      setTracking(data);
    });
  };

  const loadRequests = async () => {
    if (!user || !user.token) return;
    try {
      const response = await api.get('/requests');
      setRequests((response.data || []).filter((r) => {
        const isClaimableStatus = r.status === 'PENDING' || r.status === 'ACCEPTED';
        const assignedToOther = r.assignedVolunteer && r.assignedVolunteer.id !== user?.userId;
        return isClaimableStatus && !assignedToOther;
      }));
    } catch (err) {
      console.error('Error loading requests:', err);
      // Don't show error for 401/403 - might be user not approved yet
      if (err.response?.status !== 401 && err.response?.status !== 403) {
        const errorMessage = err.response?.data?.message || 'Unable to load open pickups.';
        enqueueSnackbar(errorMessage, { variant: 'error' });
      }
      setRequests([]);
    }
  };

  const loadMyRequests = async () => {
    if (!user || !user.token || !user.userId) return;
    try {
      const response = await api.get(`/requests/volunteer/${user.userId}`);
      setMyRequests(response.data || []);
    } catch (err) {
      console.error('Error loading my requests:', err);
      // Don't show error for 401/403 - might be user not approved yet
      if (err.response?.status !== 401 && err.response?.status !== 403) {
        const errorMessage = err.response?.data?.message || 'Unable to load your deliveries.';
        enqueueSnackbar(errorMessage, { variant: 'error' });
      }
      setMyRequests([]);
    }
  };

  const loadPoints = async () => {
    try {
      const response = await api.get(`/gamification/points/${user?.userId}`);
      setPoints(response.data);
    } catch (err) {
      console.error('Error loading points:', err);
      // Don't show error for points, just leave it empty
      setPoints(null);
    }
  };

  const loadBadges = async () => {
    try {
      const response = await api.get(`/gamification/badges/${user?.userId}`);
      setBadges(response.data || []);
    } catch {
      setBadges([]);
    }
  };

  const handleAcceptRequest = async (requestId) => {
    try {
      await api.put(`/requests/${requestId}/assign?volunteerId=${user.userId}`);
      enqueueSnackbar('Pickup assigned to you. Check your route details.', { variant: 'success' });
      loadRequests();
      loadMyRequests();
    } catch {
      enqueueSnackbar('Claim failed, someone else might have accepted it.', { variant: 'warning' });
    }
  };

  const handleStartTracking = async (request) => {
    setSelectedRequest(request);
    setPickupLocation(null);
    setDestinationLocation(null);
    subscribeToRequest(request.id);
    const [pickup, destination] = await Promise.all([
      resolvePickupLocation(request),
      resolveRequestDestination(request),
    ]);
    setPickupLocation(pickup);
    setDestinationLocation(destination);
  };

  const pushLocation = async (req, latitude, longitude) => {
    if (!req || !user?.userId) return;
    try {
      setTracking({ latitude, longitude });
      await api.post('/tracking', null, {
        params: {
          requestId: req.id,
          latitude,
          longitude,
        },
      });
    } catch {
      const now = Date.now();
      if (now - lastTrackingErrorRef.current > 8000) {
        enqueueSnackbar('Live tracking is reconnecting. Your location will retry automatically.', { variant: 'warning' });
        lastTrackingErrorRef.current = now;
      }
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

  useEffect(() => {
    if (!selectedRequest || !navigator.geolocation) {
      return undefined;
    }
    geoWatchRef.current = navigator.geolocation.watchPosition(
      (position) => {
        pushLocation(
          selectedRequest,
          position.coords.latitude,
          position.coords.longitude,
        );
      },
      () => enqueueSnackbar('Location permission required for live tracking.', { variant: 'warning' }),
      { enableHighAccuracy: true, maximumAge: 2000, timeout: 15000 },
    );
    return () => {
      if (geoWatchRef.current != null) {
        navigator.geolocation.clearWatch(geoWatchRef.current);
        geoWatchRef.current = null;
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedRequest]);

  const handleOpenDeliveredDialog = (request) => {
    setDeliveryDialog({
      open: true,
      request,
      note: '',
      imageFile: null,
      imagePreview: null,
      uploading: false,
    });
  };

  const handleProofFileChange = (event) => {
    const file = event.target.files?.[0];
    if (!file) return;
    if (!file.type.startsWith('image/')) {
      enqueueSnackbar('Please select a valid image file for delivery proof.', { variant: 'warning' });
      return;
    }
    const reader = new FileReader();
    reader.onloadend = () => {
      setDeliveryDialog((prev) => ({ ...prev, imagePreview: reader.result, imageFile: file }));
    };
    reader.readAsDataURL(file);
  };

  const uploadProofImage = async (file) => {
    const payload = new FormData();
    payload.append('file', file);
    const response = await api.post('/files/upload', payload);
    const url = response.data?.url;
    return resolveServerUrl(url);
  };

  const handleConfirmDelivered = async () => {
    const request = deliveryDialog.request;
    if (!request) return;
    if (!deliveryDialog.imageFile) {
      enqueueSnackbar('Delivery proof image is required.', { variant: 'warning' });
      return;
    }

    setDeliveryDialog((prev) => ({ ...prev, uploading: true }));
    try {
      const proofUrl = await uploadProofImage(deliveryDialog.imageFile);
      await api.put(`/requests/${request.id}/status`, null, {
        params: {
          status: 'DELIVERED',
          deliveryProofUrl: proofUrl,
          deliveryProofNote: deliveryDialog.note || null,
        },
      });
      enqueueSnackbar('Delivery confirmed. Thank you!', { variant: 'success' });
      loadMyRequests();
      setSelectedRequest(null);
      setTracking(null);
      setAnimatedPosition(null);
      setPickupLocation(null);
      setDestinationLocation(null);
      setRoutePath([]);
      setEtaSeconds(null);
      setDistanceMeters(null);
      setDeliveryDialog({
        open: false,
        request: null,
        note: '',
        imageFile: null,
        imagePreview: null,
        uploading: false,
      });
    } catch {
      enqueueSnackbar('Could not mark as delivered.', { variant: 'error' });
    } finally {
      setDeliveryDialog((prev) => ({ ...prev, uploading: false }));
    }
  };

  const handleVerifyQr = async (requestId, stage) => {
    try {
      await api.post(`/requests/${requestId}/verify-qr`, null, {
        params: { token: (qrVerificationToken || '').trim(), stage },
      });
      enqueueSnackbar(`${stage} QR verified successfully.`, { variant: 'success' });
      setQrVerificationToken('');
      loadMyRequests();
    } catch (err) {
      enqueueSnackbar(err.response?.data?.message || 'QR verification failed.', { variant: 'error' });
    }
  };

  const stats = useMemo(
    () => [
      { label: 'Open pickups', value: requests.length, icon: <Navigation /> },
      { label: 'My assignments', value: myRequests.filter((r) => r.status !== 'DELIVERED').length, icon: <LocalShipping /> },
      { label: 'Points', value: points?.totalPoints ?? 0, icon: <EmojiEvents />, color: 'secondary' },
    ],
    [requests.length, myRequests, points]
  );

  return (
    <PanelLayout
      title="Volunteer Live Ops"
      subtitle="Claim pickups, stream your GPS, and close deliveries in real-time."
      darkMode={darkMode}
      setDarkMode={setDarkMode}
    >
      <Grid container spacing={3} sx={{ mb: 3 }}>
        {stats.map((item) => (
          <Grid item xs={12} md={4} key={item.label}>
            <StatCard {...item} />
          </Grid>
        ))}
      </Grid>
      <Paper elevation={0} sx={{ p: 2, mb: 3 }}>
        <Typography variant="subtitle1" sx={{ mb: 1, fontWeight: 600 }}>Volunteer Reputation Badges</Typography>
        <Stack direction="row" spacing={1} flexWrap="wrap">
          {badges.map((b) => (
            <Chip key={b.id} label={b.badge?.name || 'Badge'} color="secondary" sx={{ mb: 1 }} />
          ))}
          {badges.length === 0 && <Chip label="Complete deliveries to earn badges" />}
        </Stack>
      </Paper>

      <Grid container spacing={3}>
        <Grid item xs={12} md={6}>
          <Paper elevation={0} sx={{ p: 3 }}>
            <Stack direction="row" justifyContent="space-between" alignItems="center" mb={2}>
              <div>
                <Typography variant="h6">Available pickups</Typography>
                <Typography variant="body2" color="text.secondary">
                  Accept a request to lock it and begin navigation.
                </Typography>
              </div>
              <Button size="small" onClick={loadRequests}>
                Refresh
              </Button>
            </Stack>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Meal</TableCell>
                  <TableCell>Pickup address</TableCell>
                  <TableCell>Status</TableCell>
                  <TableCell />
                </TableRow>
              </TableHead>
              <TableBody>
                {requests.map((request) => (
                  <TableRow key={request.id}>
                    <TableCell>{request.donation?.foodName}</TableCell>
                    <TableCell>{request.pickupAddress || request.donation?.address}</TableCell>
                    <TableCell>
                      <Chip label={request.status} size="small" color="warning" />
                    </TableCell>
                    <TableCell align="right">
                      {!request.assignedVolunteer && (
                        <Button size="small" variant="contained" onClick={() => handleAcceptRequest(request.id)}>
                          Accept
                        </Button>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Paper>
        </Grid>

        <Grid item xs={12} md={6}>
          <Paper elevation={0} sx={{ p: 3 }}>
            <Stack direction="row" justifyContent="space-between" alignItems="center" mb={2}>
              <div>
                <Typography variant="h6">My active deliveries</Typography>
                <Typography variant="body2" color="text.secondary">
                  Tap track to broadcast your live location.
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
                  <TableCell>Drop location</TableCell>
                  <TableCell>Status</TableCell>
                  <TableCell align="right">Actions</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {myRequests
                  .filter((r) => r.status !== 'DELIVERED')
                  .map((request) => (
                    <TableRow key={request.id}>
                      <TableCell>{request.donation?.foodName}</TableCell>
                      <TableCell>{request.deliveryAddress}</TableCell>
                      <TableCell>
                        <Chip label={request.status} size="small" color="info" />
                      </TableCell>
                      <TableCell align="right">
                        <Stack direction="row" spacing={1} justifyContent="flex-end">
                          <Button size="small" onClick={() => handleStartTracking(request)}>
                            Track
                          </Button>
                          <Button
                            size="small"
                            variant="outlined"
                            onClick={() => handleVerifyQr(request.id, 'PICKUP')}
                          >
                            Verify Pickup QR
                          </Button>
                          <Button
                            size="small"
                            color="success"
                            variant="contained"
                            onClick={() => handleOpenDeliveredDialog(request)}
                          >
                            Delivered
                          </Button>
                        </Stack>
                      </TableCell>
                    </TableRow>
                  ))}
              </TableBody>
            </Table>
          </Paper>
        </Grid>
      </Grid>

      {selectedRequest && (
        <Paper elevation={0} sx={{ p: 3, mt: 3 }}>
          <Typography variant="h6" mb={2}>
            Live tracking — {selectedRequest.donation?.foodName}
          </Typography>
          <Stack direction="row" spacing={2} mb={2} flexWrap="wrap">
            <Chip
              color="primary"
              label={`ETA ${formatDuration(etaSeconds)}`}
            />
            <Chip
              color="secondary"
              label={`Distance ${formatDistance(distanceMeters)}`}
            />
          </Stack>
          <MapContainer
            center={
              animatedPosition
                ? animatedPosition
                : destinationLocation || pickupLocation || [20.5937, 78.9629]
            }
            zoom={14}
            style={mapContainerStyle}
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
            {pickupLocation && destinationLocation && (
              <Polyline
                positions={[pickupLocation, destinationLocation]}
                pathOptions={{ color: '#1976d2', weight: 3, dashArray: '8 8' }}
              />
            )}
            {animatedPosition && pickupLocation && (
                <Polyline
                  positions={[
                    animatedPosition,
                    pickupLocation,
                  ]}
                  pathOptions={{ color: '#ef6c00', weight: 3 }}
                />
              )}
            <MapAutoCenter center={animatedPosition || null} />
          </MapContainer>
          <Stack direction={{ xs: 'column', md: 'row' }} spacing={2} mt={2} alignItems="center">
            <Box>
              <Typography variant="subtitle2">Pickup/Delivery QR</Typography>
              <QRCodeCanvas value={selectedRequest.qrToken || 'pending-qr'} size={120} />
            </Box>
            <Input
              placeholder="Paste scanned QR token"
              value={qrVerificationToken}
              onChange={(e) => setQrVerificationToken(e.target.value)}
              sx={{ minWidth: 260 }}
            />
            <Button variant="outlined" onClick={() => handleVerifyQr(selectedRequest.id, 'DELIVERY')}>
              Verify Delivery QR
            </Button>
          </Stack>
        </Paper>
      )}

      <Dialog
        open={deliveryDialog.open}
        onClose={() => !deliveryDialog.uploading && setDeliveryDialog((prev) => ({ ...prev, open: false }))}
        maxWidth="sm"
        fullWidth
      >
        <DialogTitle>Confirm delivery with proof</DialogTitle>
        <DialogContent dividers>
          <Stack spacing={2} mt={1}>
            <Input type="file" inputProps={{ accept: 'image/*' }} onChange={handleProofFileChange} fullWidth />
            <TextField
              label="Delivery note (optional)"
              multiline
              minRows={3}
              value={deliveryDialog.note}
              onChange={(e) => setDeliveryDialog((prev) => ({ ...prev, note: e.target.value }))}
              placeholder="Receiver name, landmark, or handover details..."
              fullWidth
            />
            {deliveryDialog.imagePreview && (
              <Box
                component="img"
                src={deliveryDialog.imagePreview}
                alt="Delivery proof preview"
                sx={{ width: '100%', maxHeight: 260, objectFit: 'cover', borderRadius: 1 }}
              />
            )}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDeliveryDialog((prev) => ({ ...prev, open: false }))} disabled={deliveryDialog.uploading}>
            Cancel
          </Button>
          <Button variant="contained" color="success" onClick={handleConfirmDelivered} disabled={deliveryDialog.uploading}>
            Confirm Delivered
          </Button>
        </DialogActions>
      </Dialog>
    </PanelLayout>
  );
};

export default VolunteerPanel;

