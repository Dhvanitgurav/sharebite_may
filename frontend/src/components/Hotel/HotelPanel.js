import React, { useEffect, useState, useMemo, useRef, useCallback } from 'react';
import {
  Grid,
  Paper,
  Typography,
  Chip,
  Stack,
  Button,
  Table,
  TableHead,
  TableRow,
  TableCell,
  TableBody,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  TextField,
  MenuItem,
  Box,
  Alert,
  LinearProgress,
  Divider,
  FormControlLabel,
  Switch,
} from '@mui/material';
import { LoadingButton } from '@mui/lab';
import {
  AddRounded,
  Inventory2Rounded,
  EmojiEvents,
  MapRounded,
  ScienceOutlined,
  AutoAwesome,
  PictureAsPdf,
} from '@mui/icons-material';
import dayjs from 'dayjs';
import { useSnackbar } from 'notistack';
import SockJS from 'sockjs-client';
import { Client } from '@stomp/stompjs';
import { MapContainer, TileLayer, Marker, Popup, Polyline, useMap } from 'react-leaflet';
import PanelLayout from '../Layout/PanelLayout';
import StatCard from '../Common/StatCard';
import api from '../../utils/api';
import { WS_SOCKJS_URL, resolveServerUrl } from '../../utils/appConfig';
import { useAuth } from '../../context/AuthContext';
import { resolvePickupLocation, resolveRequestDestination } from '../../utils/location';
import L from 'leaflet';
import { formatDistance, formatDuration, getRoute } from '../../utils/routing';
import { bearingDeg } from '../../utils/geo';

const donationTypes = [
  { value: 'HUMAN', label: 'Human Consumption' },
  { value: 'DOG', label: 'Stray Dogs' },
  { value: 'COMPOST', label: 'Compost' },
];

const MapAutoCenter = ({ center }) => {
  const map = useMap();
  useEffect(() => {
    if (center?.[0] && center?.[1]) {
      map.setView(center, map.getZoom(), { animate: true });
    }
  }, [center, map]);
  return null;
};

const HotelPanel = ({ darkMode, setDarkMode }) => {
  const { user } = useAuth();
  const [donations, setDonations] = useState([]);
  const [points, setPoints] = useState(null);
  const [donationRequests, setDonationRequests] = useState([]);
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
  const animationFrameRef = useRef(null);
  const [openForm, setOpenForm] = useState(false);
  const [proofDialog, setProofDialog] = useState({ open: false, request: null });
  const [fingerprintDialog, setFingerprintDialog] = useState({ open: false, requestId: null, data: null, loading: false });
  const routeDebounceRef = useRef(null);
  const [submitting, setSubmitting] = useState(false);
  const { enqueueSnackbar } = useSnackbar();
  const [formData, setFormData] = useState({
    foodName: '',
    description: '',
    quantity: '',
    expiryDate: '',
    donationType: 'HUMAN',
    address: '',
    latitude: '',
    longitude: '',
    photoUrl: '',
    mlPolicyOverrideReason: '',
  });
  const [imageFile, setImageFile] = useState(null);
  const [imagePreview, setImagePreview] = useState(null);
  const [uploadingImage, setUploadingImage] = useState(false);
  /** Last CNN result from Spring → Python `/predict` (same JWT as rest of app). */
  const [mlPrediction, setMlPrediction] = useState(null);
  const [checkingMl, setCheckingMl] = useState(false);
  const [mlAiError, setMlAiError] = useState(null);
  /** When on, adding a photo auto-runs AI and applies safe defaults (expiry / compost routing). */
  const [autoMlAssist, setAutoMlAssist] = useState(true);
  const [riderBearing, setRiderBearing] = useState(0);

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
      refreshData();
      const stomp = connectWebSocket();
      return () => stomp?.deactivate();
    }
  }, [user]);

  const refreshData = () => {
    loadDonations();
    loadPoints();
    loadDonationRequests();
  };

  const handleDownloadDonationsPdf = async () => {
    if (!user?.userId || !user?.token) {
      enqueueSnackbar('Sign in to download your donation record.', { variant: 'warning' });
      return;
    }
    try {
      const res = await api.get(`/donations/donor/${user.userId}/export/pdf`, { responseType: 'blob' });
      const blob = new Blob([res.data], { type: 'application/pdf' });
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `sharebite-donations-${user.userId}.pdf`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      window.URL.revokeObjectURL(url);
      enqueueSnackbar('Donation PDF downloaded.', { variant: 'success' });
    } catch (err) {
      enqueueSnackbar(
        err.response?.data?.message || err.message || 'Could not download PDF.',
        { variant: 'error' }
      );
    }
  };

  const loadDonations = async () => {
    if (!user || !user.token || !user.userId) return;
    try {
      const response = await api.get(`/donations/donor/${user.userId}`);
      setDonations(response.data || []);
    } catch (err) {
      console.error('Error loading donations:', err);
      // Don't show error for 401/403 - might be user not approved yet
      if (err.response?.status !== 401 && err.response?.status !== 403) {
        const errorMessage = err.response?.data?.message || 'Unable to fetch donations.';
        enqueueSnackbar(errorMessage, { variant: 'error' });
      }
      setDonations([]);
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

  const loadDonationRequests = async () => {
    if (!user?.userId) return;
    try {
      const response = await api.get(`/requests/donor/${user.userId}`);
      setDonationRequests(response.data || []);
    } catch (err) {
      if (err.response?.status !== 401 && err.response?.status !== 403) {
        enqueueSnackbar('Unable to load pickup requests for your donations.', { variant: 'error' });
      }
      setDonationRequests([]);
    }
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
      // no live point available yet
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

  const handleFormChange = (field) => (event) => {
    setFormData((prev) => ({ ...prev, [field]: event.target.value }));
  };

  const handleImageChange = (event) => {
    const file = event.target.files[0];
    if (file) {
      if (!file.type.startsWith('image/')) {
        enqueueSnackbar('Please select an image file', { variant: 'error' });
        return;
      }
      setMlPrediction(null);
      setMlAiError(null);
      setImageFile(file);
      const reader = new FileReader();
      reader.onloadend = () => {
        setImagePreview(reader.result);
      };
      reader.readAsDataURL(file);
    }
  };

  const uploadImage = async () => {
    if (!imageFile) return null;
    
    // Verify user is authenticated - always check localStorage directly
    const token = localStorage.getItem('token');
    if (!token) {
      enqueueSnackbar('You must be logged in to upload images.', { variant: 'error' });
      return null;
    }
    
    if (!user || !user.userId) {
      enqueueSnackbar('User information is missing. Please log in again.', { variant: 'error' });
      return null;
    }
    
    setUploadingImage(true);
    try {
      const formData = new FormData();
      formData.append('file', imageFile);
      
      // Double-check token is still valid before upload
      const freshToken = localStorage.getItem('token');
      if (!freshToken) {
        enqueueSnackbar('Your session has expired. Please log in again.', { variant: 'error' });
        setUploadingImage(false);
        return null;
      }
      
      // Make the upload request
      // The interceptor will:
      // 1. Get token from localStorage
      // 2. Set Authorization header
      // 3. Remove Content-Type to let browser set it with boundary
      const response = await api.post('/files/upload', formData);
      
      return response.data.url;
    } catch (err) {
      console.error('Error uploading image:', err);
      console.error('Response status:', err.response?.status);
      console.error('Response data:', err.response?.data);
      console.error('Request config:', {
        url: err.config?.url,
        method: err.config?.method,
        hasAuthHeader: !!err.config?.headers?.Authorization,
        authHeader: err.config?.headers?.Authorization ? err.config.headers.Authorization.substring(0, 30) + '...' : 'none',
        isFormData: err.config?.data instanceof FormData
      });
      
      const errorMessage = err.response?.data?.message || err.response?.data?.error || 'Failed to upload image. Please try again.';
      
      // Handle different error types
      if (err.response?.status === 401 || err.response?.status === 403) {
        // Check if it's really an auth error or just permission issue
        const errorData = err.response?.data;
        const errorMsg = (errorData?.message || '').toLowerCase();
        if (errorMsg.includes('token') || errorMsg.includes('expired') || errorMsg.includes('invalid') || errorMsg.includes('authentication') || errorMsg.includes('unauthorized')) {
          enqueueSnackbar('Your session has expired. Please log in again.', { variant: 'error' });
        } else {
          enqueueSnackbar('Unable to upload. Please ensure your account is approved.', { variant: 'warning' });
        }
      } else if (err.response?.status === 500) {
        enqueueSnackbar('Server error occurred. Please try again later.', { variant: 'error' });
      } else {
        enqueueSnackbar(errorMessage, { variant: 'error' });
      }
      
      return null;
    } finally {
      setUploadingImage(false);
    }
  };

  const mlExpiryHours = (pred) =>
    pred == null ? null : pred.estimated_expiry_hours ?? pred.estimatedExpiryHours ?? null;

  const runFreshnessPrediction = useCallback(
    async ({ silent = false, applyAutoSuggestions = false } = {}) => {
      if (!imageFile) return;
      setCheckingMl(true);
      setMlAiError(null);
      try {
        const fd = new FormData();
        fd.append('file', imageFile);
        const res = await api.post('/freshness/ml/predict', fd);
        const data = res.data;
        setMlPrediction(data);
        if (applyAutoSuggestions && autoMlAssist) {
          if (data.status === 'ROTTEN' && Number(data.confidence) >= 0.6) {
            setFormData((prev) => ({ ...prev, donationType: 'COMPOST' }));
          }
          if (data.status === 'FRESH' && Number(data.confidence) >= 0.6) {
            const h = data.estimated_expiry_hours ?? data.estimatedExpiryHours;
            if (h != null && h > 0) {
              setFormData((prev) => {
                if (prev.expiryDate) return prev;
                const suggested = dayjs().add(h, 'hour').format('YYYY-MM-DDTHH:mm');
                return { ...prev, expiryDate: suggested };
              });
            }
          }
        }
        if (!silent) {
          enqueueSnackbar(
            `Freshness AI: ${data.status} (${(Number(data.confidence) * 100).toFixed(0)}% confidence)`,
            { variant: data.status === 'ROTTEN' ? 'warning' : 'success' }
          );
          if (data.status === 'ROTTEN' && Number(data.confidence) >= 0.6) {
            enqueueSnackbar('Donation type set to Compost — unsafe for human consumption.', { variant: 'info' });
          }
          if (data.message) enqueueSnackbar(data.message, { variant: 'default' });
        }
      } catch (err) {
        const code = err.response?.data?.error;
        const msg =
          err.response?.data?.message ||
          (code === 'FRESHNESS_ML_UNAVAILABLE'
            ? 'AI service offline — start the Python API (uvicorn) or check freshness.api.base-url.'
            : 'Freshness AI unavailable.');
        setMlAiError(msg);
        setMlPrediction(null);
        if (!silent) enqueueSnackbar(msg, { variant: 'error' });
      } finally {
        setCheckingMl(false);
      }
    },
    [imageFile, autoMlAssist, enqueueSnackbar]
  );

  /** When automatic assistance is on, analyze each new photo after a short debounce. */
  useEffect(() => {
    if (!openForm || !imageFile || !autoMlAssist) return undefined;
    const t = setTimeout(() => {
      runFreshnessPrediction({ silent: true, applyAutoSuggestions: true });
    }, 700);
    return () => clearTimeout(t);
  }, [imageFile, openForm, autoMlAssist, runFreshnessPrediction]);

  const handleCheckFreshnessMl = () => {
    if (!imageFile) {
      enqueueSnackbar('Add a food photo first.', { variant: 'warning' });
      return;
    }
    runFreshnessPrediction({ silent: false, applyAutoSuggestions: autoMlAssist });
  };

  const applyMlSuggestedExpiry = () => {
    const hours = mlExpiryHours(mlPrediction);
    if (hours == null || hours <= 0) {
      enqueueSnackbar('No positive suggested expiry from the AI for this result.', { variant: 'info' });
      return;
    }
    const suggested = dayjs().add(hours, 'hour').format('YYYY-MM-DDTHH:mm');
    setFormData((prev) => ({ ...prev, expiryDate: suggested }));
    enqueueSnackbar(`Expiry set to about ${hours} hour(s) from now.`, { variant: 'success' });
  };

  const handleCreateDonation = async (e) => {
    e.preventDefault();
    
    // Verify user is authenticated
    if (!user || !user.userId) {
      enqueueSnackbar('You must be logged in to create donations.', { variant: 'error' });
      return;
    }
    
    const token = localStorage.getItem('token');
    if (!token) {
      enqueueSnackbar('Your session has expired. Please log in again.', { variant: 'error' });
      return;
    }
    
    setSubmitting(true);
    try {
      // Upload image first if present
      let photoUrl = formData.photoUrl;
      if (imageFile) {
        const uploadedUrl = await uploadImage();
        if (!uploadedUrl) {
          setSubmitting(false);
          return;
        }
        // Ensure full URL is stored
        photoUrl = resolveServerUrl(uploadedUrl);
      }

      console.log('Creating donation with userId:', user.userId);
      console.log('Token present:', !!localStorage.getItem('token'));
      
      const donationData = {
        ...formData,
        photoUrl: photoUrl || null,
        quantity: parseInt(formData.quantity, 10),
        latitude: formData.latitude ? parseFloat(formData.latitude) : null,
        longitude: formData.longitude ? parseFloat(formData.longitude) : null,
      };
      delete donationData.mlPolicyOverrideReason;
      const override = (formData.mlPolicyOverrideReason || '').trim();
      if (override.length > 0) {
        donationData.mlPolicyOverrideReason = override;
      }
      if (mlPrediction) {
        donationData.mlFreshnessStatus = mlPrediction.status;
        donationData.mlFreshnessConfidence = mlPrediction.confidence;
        donationData.mlFreshnessMessage = mlPrediction.message;
        const eh = mlExpiryHours(mlPrediction);
        if (eh != null) {
          donationData.mlEstimatedExpiryHours = eh;
        }
      }
      
      console.log('Donation data:', donationData);
      
      const response = await api.post(`/donations?donorId=${user.userId}`, donationData);
      console.log('Donation created successfully:', response.data);
      
      enqueueSnackbar('Donation published successfully!', { variant: 'success' });
      setFormData({
        foodName: '',
        description: '',
        quantity: '',
        expiryDate: '',
        donationType: 'HUMAN',
        address: '',
        latitude: '',
        longitude: '',
        photoUrl: '',
        mlPolicyOverrideReason: '',
      });
      setImageFile(null);
      setImagePreview(null);
      setMlPrediction(null);
      setMlAiError(null);
      setOpenForm(false);
      refreshData();
    } catch (err) {
      console.error('Error creating donation:', err);
      console.error('Response status:', err.response?.status);
      console.error('Response data:', err.response?.data);
      console.error('Request config:', err.config);
      
      const errorType = err.response?.data?.error;
      const errorMessage = err.response?.data?.message || 
                          err.response?.data?.error || 
                          'Failed to publish donation. Please review the form.';
      
      if (errorType === 'ML_SAFETY_BLOCK') {
        enqueueSnackbar(
          'Server AI blocked this listing for human/dog food. Switch to COMPOST or add a 15+ character override reason in the form.',
          { variant: 'warning' }
        );
      } else if (err.response?.status === 401 || err.response?.status === 403) {
        enqueueSnackbar('Authentication failed. Your session may have expired. Please log in again.', { variant: 'error' });
      } else {
        enqueueSnackbar(errorMessage, { variant: 'error' });
      }
    } finally {
      setSubmitting(false);
    }
  };

  const statItems = useMemo(
    () => [
      {
        label: 'Total donations',
        value: donations.length,
        icon: <Inventory2Rounded />,
        color: 'primary',
      },
      {
        label: 'Total points',
        value: points?.totalPoints ?? 0,
        icon: <EmojiEvents />,
        color: 'secondary',
      },
      {
        label: 'Level',
        value: points?.level ?? 1,
        icon: <MapRounded />,
        color: 'primary',
      },
    ],
    [donations.length, points]
  );

  const statusColor = (status) => {
    switch (status) {
      case 'DELIVERED':
      case 'COMPOSTED':
        return 'success';
      case 'PENDING':
        return 'warning';
      case 'EXPIRED':
      case 'REJECTED':
        return 'error';
      default:
        return 'info';
    }
  };

  const handleOpenProofDialog = (request) => {
    setProofDialog({ open: true, request });
  };

  const handleViewFingerprint = async (requestId) => {
    setFingerprintDialog({ open: true, requestId, data: null, loading: true });
    try {
      const res = await api.get(`/requests/${requestId}/proof-fingerprint`);
      setFingerprintDialog({ open: true, requestId, data: res.data, loading: false });
    } catch {
      setFingerprintDialog({ open: true, requestId, data: null, loading: false });
      enqueueSnackbar('Unable to load proof fingerprint.', { variant: 'error' });
    }
  };

  return (
    <PanelLayout
      title="Donor Command Center"
      subtitle="List surplus meals, track pickups, and grow your impact score."
      actions={
        <Button
          variant="contained"
          startIcon={<AddRounded />}
          onClick={() => {
            setMlPrediction(null);
            setMlAiError(null);
            setOpenForm(true);
          }}
        >
          Publish donation
        </Button>
      }
      darkMode={darkMode}
      setDarkMode={setDarkMode}
    >
      <Grid container spacing={3} sx={{ mb: 3 }}>
        {statItems.map((item) => (
          <Grid item xs={12} md={4} key={item.label}>
            <StatCard {...item} />
          </Grid>
        ))}
      </Grid>

      <Paper elevation={0} sx={{ p: 3 }}>
        <Stack direction="row" justifyContent="space-between" alignItems="center" mb={2} flexWrap="wrap" gap={1}>
          <div>
            <Typography variant="h6">Donation history</Typography>
            <Typography variant="body2" color="text.secondary">
              Track fulfilment, expiries, and compost pickups.
            </Typography>
          </div>
          <Stack direction="row" spacing={1}>
            <Button size="small" variant="outlined" startIcon={<PictureAsPdf />} onClick={handleDownloadDonationsPdf}>
              Download PDF
            </Button>
            <Button size="small" onClick={refreshData}>
              Refresh
            </Button>
          </Stack>
        </Stack>
        <Table size="medium">
          <TableHead>
            <TableRow>
              <TableCell>Food</TableCell>
              <TableCell>Type</TableCell>
              <TableCell>AI</TableCell>
              <TableCell>Quantity</TableCell>
              <TableCell>Expiry</TableCell>
              <TableCell>Status</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {donations.length === 0 && (
              <TableRow>
                <TableCell colSpan={6}>
                  <Typography variant="body2" align="center" color="text.secondary">
                    No donations yet. Share your first batch today!
                  </Typography>
                </TableCell>
              </TableRow>
            )}
            {donations.map((donation) => (
              <TableRow key={donation.id}>
                <TableCell>
                  <Stack direction="row" spacing={2}>
                    {donation.photoUrl && (
                      <Box
                        component="img"
                        src={resolveServerUrl(donation.photoUrl)}
                        alt={donation.foodName}
                        sx={{
                          width: 60,
                          height: 60,
                          objectFit: 'cover',
                          borderRadius: 1,
                        }}
                      />
                    )}
                    <Box>
                      <Typography variant="subtitle2">{donation.foodName}</Typography>
                      <Typography variant="caption" color="text.secondary">
                        {donation.description || '—'}
                      </Typography>
                    </Box>
                  </Stack>
                </TableCell>
                <TableCell>
                  <Chip label={donation.donationType} size="small" />
                </TableCell>
                <TableCell>
                  {donation.mlFreshnessStatus ? (
                    <Chip
                      size="small"
                      label={donation.mlFreshnessStatus}
                      color={donation.mlFreshnessStatus === 'FRESH' ? 'success' : 'error'}
                      variant="outlined"
                    />
                  ) : (
                    <Typography variant="caption" color="text.secondary">
                      —
                    </Typography>
                  )}
                </TableCell>
                <TableCell>{donation.quantity}</TableCell>
                <TableCell>{dayjs(donation.expiryDate).format('MMM DD, YYYY HH:mm')}</TableCell>
                <TableCell>
                  <Chip
                    label={donation.status}
                    size="small"
                    color={statusColor(donation.status)}
                  />
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Paper>

      <Paper elevation={0} sx={{ p: 3, mt: 3 }}>
        <Stack direction="row" justifyContent="space-between" alignItems="center" mb={2}>
          <div>
            <Typography variant="h6">Live request tracking</Typography>
            <Typography variant="body2" color="text.secondary">
              Follow volunteer movement and verify delivery proof for your donations.
            </Typography>
          </div>
          <Button size="small" onClick={loadDonationRequests}>
            Refresh
          </Button>
        </Stack>
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Meal</TableCell>
              <TableCell>Requester</TableCell>
              <TableCell>Status</TableCell>
              <TableCell align="right">Track</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {donationRequests.map((request) => (
              <TableRow key={request.id}>
                <TableCell>{request.donation?.foodName}</TableCell>
                <TableCell>{request.requester?.fullName || request.requesterType}</TableCell>
                <TableCell>
                  <Chip label={request.status} size="small" color={statusColor(request.status)} />
                </TableCell>
                <TableCell align="right">
                  {(request.assignedVolunteer || request.status === 'COMPOSTED') && (
                    <Button size="small" onClick={() => handleTrackRequest(request)}>
                      {request.status === 'DELIVERED' || request.status === 'COMPOSTED' ? 'Details' : 'Live'}
                    </Button>
                  )}
                  {(request.status === 'DELIVERED' || request.status === 'COMPOSTED') && (
                    <Button size="small" sx={{ ml: 1 }} onClick={() => handleViewFingerprint(request.id)}>
                      Fingerprint
                    </Button>
                  )}
                  {request.status === 'COMPOSTED' && request.compostProofUrl && (
                    <Button size="small" sx={{ ml: 1 }} onClick={() => handleOpenProofDialog(request)}>
                      Compost Proof
                    </Button>
                  )}
                </TableCell>
              </TableRow>
            ))}
            {donationRequests.length === 0 && (
              <TableRow>
                <TableCell colSpan={4}>
                  <Typography variant="body2" color="text.secondary" align="center">
                    No pickup requests yet for your donations.
                  </Typography>
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </Paper>

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
          {selectedTrackingRequest.status === 'COMPOSTED' && selectedTrackingRequest.compostProofUrl && (
            <Stack spacing={1} mt={2}>
              <Typography variant="subtitle2">Compost proof</Typography>
              <Box
                component="img"
                src={selectedTrackingRequest.compostProofUrl}
                alt="Compost proof"
                sx={{ width: 260, maxWidth: '100%', borderRadius: 1 }}
              />
              {selectedTrackingRequest.compostProofNote && (
                <Typography variant="body2" color="text.secondary">
                  {selectedTrackingRequest.compostProofNote}
                </Typography>
              )}
            </Stack>
          )}
        </Paper>
      )}

      <Dialog
        open={proofDialog.open}
        onClose={() => setProofDialog({ open: false, request: null })}
        maxWidth="sm"
        fullWidth
      >
        <DialogTitle>Compost completion proof</DialogTitle>
        <DialogContent dividers>
          {proofDialog.request?.compostProofUrl ? (
            <Stack spacing={2}>
              <Box
                component="img"
                src={proofDialog.request.compostProofUrl}
                alt="Compost proof"
                sx={{ width: '100%', maxHeight: 320, objectFit: 'cover', borderRadius: 1 }}
              />
              {proofDialog.request.compostProofNote && (
                <Typography variant="body2" color="text.secondary">
                  {proofDialog.request.compostProofNote}
                </Typography>
              )}
              <Chip
                color="success"
                label={`Composted on ${dayjs(proofDialog.request.compostedAt || proofDialog.request.updatedAt).format('MMM DD, YYYY HH:mm')}`}
              />
            </Stack>
          ) : (
            <Typography variant="body2" color="text.secondary">
              Proof not uploaded yet.
            </Typography>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setProofDialog({ open: false, request: null })}>Close</Button>
        </DialogActions>
      </Dialog>

      <Dialog open={fingerprintDialog.open} onClose={() => setFingerprintDialog({ open: false, requestId: null, data: null, loading: false })} maxWidth="sm" fullWidth>
        <DialogTitle>Proof integrity fingerprint</DialogTitle>
        <DialogContent dividers>
          {fingerprintDialog.loading ? (
            <Typography variant="body2" color="text.secondary">Loading fingerprint...</Typography>
          ) : fingerprintDialog.data ? (
            <Stack spacing={1}>
              <Chip label={`Request #${fingerprintDialog.data.requestId}`} size="small" />
              <Chip label={`Status ${fingerprintDialog.data.status}`} size="small" color="info" />
              <Typography variant="caption" color="text.secondary">SHA-256 digest</Typography>
              <Typography variant="body2" sx={{ fontFamily: 'monospace', wordBreak: 'break-all' }}>
                {fingerprintDialog.data.fingerprint}
              </Typography>
            </Stack>
          ) : (
            <Typography variant="body2" color="text.secondary">Fingerprint unavailable.</Typography>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setFingerprintDialog({ open: false, requestId: null, data: null, loading: false })}>Close</Button>
        </DialogActions>
      </Dialog>

      <Dialog
        open={openForm}
        onClose={() => {
          setOpenForm(false);
          setMlAiError(null);
        }}
        maxWidth="md"
        fullWidth
      >
        <DialogTitle>Publish new donation</DialogTitle>
        <DialogContent dividers>
          <Stack spacing={2} mt={1}>
            <TextField
              label="Food name"
              value={formData.foodName}
              onChange={handleFormChange('foodName')}
              required
              fullWidth
            />
            <TextField
              label="Description"
              value={formData.description}
              onChange={handleFormChange('description')}
              multiline
              minRows={2}
              fullWidth
            />
            <Grid container spacing={2}>
              <Grid item xs={12} md={4}>
                <TextField
                  label="Quantity (meals)"
                  type="number"
                  value={formData.quantity}
                  onChange={handleFormChange('quantity')}
                  required
                  fullWidth
                />
              </Grid>
              <Grid item xs={12} md={4}>
                <TextField
                  label="Expiry date"
                  type="datetime-local"
                  value={formData.expiryDate}
                  onChange={handleFormChange('expiryDate')}
                  required
                  fullWidth
                  InputLabelProps={{ shrink: true }}
                />
              </Grid>
              <Grid item xs={12} md={4}>
                <TextField
                  select
                  label="Donation type"
                  value={formData.donationType}
                  onChange={handleFormChange('donationType')}
                  fullWidth
                >
                  {donationTypes.map((option) => (
                    <MenuItem key={option.value} value={option.value}>
                      {option.label}
                    </MenuItem>
                  ))}
                </TextField>
              </Grid>
            </Grid>
            <TextField
              label="Pickup address"
              value={formData.address}
              onChange={handleFormChange('address')}
              multiline
              minRows={2}
              fullWidth
            />
            <Grid container spacing={2}>
              <Grid item xs={12} md={6}>
                <TextField
                  label="Latitude"
                  type="number"
                  value={formData.latitude}
                  onChange={handleFormChange('latitude')}
                  fullWidth
                />
              </Grid>
              <Grid item xs={12} md={6}>
                <TextField
                  label="Longitude"
                  type="number"
                  value={formData.longitude}
                  onChange={handleFormChange('longitude')}
                  fullWidth
                />
              </Grid>
            </Grid>
            <TextField
              label="Food Image"
              type="file"
              inputProps={{ accept: 'image/*' }}
              onChange={handleImageChange}
              fullWidth
              InputLabelProps={{ shrink: true }}
            />
            {imagePreview && (
              <Box sx={{ mt: 2, textAlign: 'center' }}>
                <img 
                  src={imagePreview} 
                  alt="Preview" 
                  style={{ maxWidth: '100%', maxHeight: '200px', borderRadius: '8px' }}
                />
              </Box>
            )}
            <Paper
              variant="outlined"
              sx={{
                p: 2,
                borderRadius: 2,
                bgcolor: (theme) =>
                  theme.palette.mode === 'dark' ? 'rgba(46, 125, 50, 0.08)' : 'rgba(46, 125, 50, 0.04)',
                borderColor: (theme) => theme.palette.divider,
              }}
            >
              <Stack spacing={1.5}>
                <Stack direction="row" alignItems="center" justifyContent="space-between" flexWrap="wrap" gap={1}>
                  <Stack direction="row" alignItems="center" spacing={1}>
                    <AutoAwesome color="primary" fontSize="small" />
                    <Typography variant="subtitle1" fontWeight={600}>
                      Smart food assessment
                    </Typography>
                  </Stack>
                  <FormControlLabel
                    control={
                      <Switch
                        checked={autoMlAssist}
                        onChange={(e) => setAutoMlAssist(e.target.checked)}
                        color="primary"
                        size="small"
                      />
                    }
                    label={<Typography variant="body2">Automatic AI assist</Typography>}
                    sx={{ m: 0 }}
                  />
                </Stack>
                <Typography variant="caption" color="text.secondary">
                  Runs automatically when you attach a photo (debounced). Turn off for manual control only.
                </Typography>
                <Divider />
                {!imageFile && (
                  <Typography variant="body2" color="text.secondary">
                    Add a food photo — when auto-assist is on, we analyze it automatically.
                  </Typography>
                )}
                {imageFile && !autoMlAssist && !mlPrediction && !checkingMl && !mlAiError && (
                  <LoadingButton
                    variant="contained"
                    size="small"
                    startIcon={<ScienceOutlined />}
                    onClick={() => runFreshnessPrediction({ silent: false, applyAutoSuggestions: false })}
                  >
                    Run AI assessment
                  </LoadingButton>
                )}
                {imageFile && checkingMl && !mlPrediction && !mlAiError && (
                  <Box>
                    <Typography variant="body2" color="text.secondary" gutterBottom>
                      Analyzing image…
                    </Typography>
                    <LinearProgress />
                  </Box>
                )}
                {mlAiError && (
                  <Alert severity="warning" onClose={() => setMlAiError(null)}>
                    {mlAiError}
                  </Alert>
                )}
                {mlPrediction && (
                  <Stack spacing={1.25}>
                    <Stack direction="row" alignItems="center" spacing={1} flexWrap="wrap">
                      <Chip
                        size="small"
                        label={mlPrediction.status}
                        color={mlPrediction.status === 'ROTTEN' ? 'error' : 'success'}
                      />
                      <Typography variant="body2" color="text.secondary">
                        Model confidence
                      </Typography>
                    </Stack>
                    <LinearProgress
                      variant="determinate"
                      value={Math.min(100, Number(mlPrediction.confidence) * 100)}
                      sx={{ height: 8, borderRadius: 1 }}
                      color={mlPrediction.status === 'ROTTEN' ? 'error' : 'success'}
                    />
                    <Typography variant="body2">{mlPrediction.message}</Typography>
                    {(() => {
                      const probs =
                        mlPrediction.class_probabilities || mlPrediction.classProbabilities;
                      if (!probs || typeof probs !== 'object') return null;
                      const parts = Object.entries(probs).map(
                        ([k, v]) => `${k}: ${(Number(v) * 100).toFixed(1)}%`
                      );
                      if (!parts.length) return null;
                      return (
                        <Typography variant="caption" color="text.secondary" display="block">
                          Class scores (CNN + class_indices): {parts.join(' · ')}
                        </Typography>
                      );
                    })()}
                    {(mlPrediction.predicted_class_index != null ||
                      mlPrediction.predictedClassIndex != null) && (
                      <Typography variant="caption" color="text.secondary" display="block">
                        Argmax class index:{' '}
                        {mlPrediction.predicted_class_index ?? mlPrediction.predictedClassIndex}
                      </Typography>
                    )}
                    {mlExpiryHours(mlPrediction) != null && (
                      <Typography variant="caption" color="text.secondary">
                        Suggested pickup window: ~{mlExpiryHours(mlPrediction)} h — official expiry above
                        {autoMlAssist ? ' (auto-filled if it was empty).' : '.'}
                      </Typography>
                    )}
                    <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} alignItems={{ sm: 'center' }}>
                      <LoadingButton
                        size="small"
                        variant="outlined"
                        startIcon={<ScienceOutlined />}
                        loading={checkingMl}
                        onClick={handleCheckFreshnessMl}
                      >
                        Re-analyze
                      </LoadingButton>
                      <Button
                        size="small"
                        variant="text"
                        disabled={!mlPrediction || (mlExpiryHours(mlPrediction) ?? 0) <= 0}
                        onClick={applyMlSuggestedExpiry}
                      >
                        Apply suggested expiry again
                      </Button>
                    </Stack>
                  </Stack>
                )}
              </Stack>
            </Paper>
            <Alert severity="info" sx={{ mt: 2 }}>
              <Typography variant="body2">
                When you click <strong>Publish donation</strong>, the <strong>server</strong> runs the freshness model on
                your uploaded image and saves that result — not the browser preview alone — so listings stay trustworthy.
              </Typography>
            </Alert>
            <TextField
              label="Safety override reason (stored if you need it)"
              value={formData.mlPolicyOverrideReason}
              onChange={handleFormChange('mlPolicyOverrideReason')}
              multiline
              minRows={2}
              fullWidth
              placeholder="Only if publish is blocked: server AI = ROTTEN but type is Human/Dog — min 15 characters, audit trail."
              inputProps={{ maxLength: 1000 }}
              helperText="Otherwise leave blank. Prefer changing donation type to Compost when food is not safe for people or dogs."
              sx={{ mt: 1 }}
            />
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 3, py: 2 }}>
          <Button
            onClick={() => {
              setOpenForm(false);
              setMlPrediction(null);
              setMlAiError(null);
            }}
            color="inherit"
          >
            Cancel
          </Button>
          <LoadingButton
            variant="contained"
            loading={submitting}
            onClick={handleCreateDonation}
          >
            Publish donation
          </LoadingButton>
        </DialogActions>
      </Dialog>
    </PanelLayout>
  );
};

export default HotelPanel;

