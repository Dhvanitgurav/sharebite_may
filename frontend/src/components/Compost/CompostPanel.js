import React, { useEffect, useMemo, useState } from 'react';
import {
  Grid,
  Paper,
  Typography,
  Card,
  CardContent,
  CardActions,
  Stack,
  Chip,
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
  Box,
  Input,
} from '@mui/material';
import dayjs from 'dayjs';
import { useSnackbar } from 'notistack';
import api from '../../utils/api';
import { resolveServerUrl } from '../../utils/appConfig';
import PanelLayout from '../Layout/PanelLayout';
import StatCard from '../Common/StatCard';
import { useAuth } from '../../context/AuthContext';
import { Agriculture, Recycling } from '@mui/icons-material';

const CompostPanel = ({ darkMode, setDarkMode }) => {
  const { user } = useAuth();
  const { enqueueSnackbar } = useSnackbar();
  const [availableDonations, setAvailableDonations] = useState([]);
  const [myRequests, setMyRequests] = useState([]);
  const [compostDialog, setCompostDialog] = useState({
    open: false,
    request: null,
    note: '',
    imageFile: null,
    imagePreview: null,
    uploading: false,
  });

  useEffect(() => {
    if (user && user.userId) {
      loadAvailableDonations();
      loadMyRequests();
    }
  }, [user]);

  const loadAvailableDonations = async () => {
    if (!user || !user.token) return;
    try {
      const response = await api.get('/donations/available/COMPOST');
      setAvailableDonations(response.data || []);
    } catch (err) {
      // Don't show error for 401/403 - might be user not approved yet
      if (err.response?.status !== 401 && err.response?.status !== 403) {
        enqueueSnackbar('Unable to fetch compost-ready batches.', { variant: 'error' });
      }
      setAvailableDonations([]);
    }
  };

  const loadMyRequests = async () => {
    if (!user || !user.token || !user.userId) return;
    try {
      const response = await api.get(`/compost`);
      setMyRequests(response.data || []);
    } catch (err) {
      // Don't show error for 401/403 - might be user not approved yet
      if (err.response?.status !== 401 && err.response?.status !== 403) {
        enqueueSnackbar('Unable to fetch your pickups.', { variant: 'error' });
      }
      setMyRequests([]);
    }
  };

  const handleRequestDonation = async (donationId) => {
    try {
      await api.post(
        `/requests?donationId=${donationId}&requesterId=${user.userId}&requesterType=COMPOST_AGENCY`
      );
      enqueueSnackbar('Pickup booked. Coordinate with the donor for timing.', { variant: 'success' });
      loadAvailableDonations();
      loadMyRequests();
    } catch {
      enqueueSnackbar('Unable to request this batch.', { variant: 'warning' });
    }
  };

  const handleOpenCompostDialog = (request) => {
    setCompostDialog({
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
      enqueueSnackbar('Please select a valid compost photo.', { variant: 'warning' });
      return;
    }
    const reader = new FileReader();
    reader.onloadend = () => {
      setCompostDialog((prev) => ({ ...prev, imagePreview: reader.result, imageFile: file }));
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

  const handleMarkComposted = async () => {
    const request = compostDialog.request;
    if (!request) return;
    if (!compostDialog.imageFile) {
      enqueueSnackbar('Compost photo is required before completion.', { variant: 'warning' });
      return;
    }
    setCompostDialog((prev) => ({ ...prev, uploading: true }));
    try {
      const proofUrl = await uploadProofImage(compostDialog.imageFile);
      await api.put(`/compost/${request.id}/status`, null, {
        params: {
          status: 'COMPOSTED',
          proofUrl,
        },
      });
      enqueueSnackbar('Marked as composted with proof. Impact recorded!', { variant: 'success' });
      loadMyRequests();
      setCompostDialog({
        open: false,
        request: null,
        note: '',
        imageFile: null,
        imagePreview: null,
        uploading: false,
      });
    } catch {
      enqueueSnackbar('Failed to update compost status.', { variant: 'error' });
    } finally {
      setCompostDialog((prev) => ({ ...prev, uploading: false }));
    }
  };

  const stats = useMemo(
    () => [
      { label: 'Available batches', value: availableDonations.length, icon: <Agriculture /> },
      { label: 'Completed compost', value: myRequests.filter((r) => r.status === 'COMPLETED').length, icon: <Recycling /> },
      { label: 'Active pickups', value: myRequests.filter((r) => r.status !== 'COMPLETED').length },
    ],
    [availableDonations.length, myRequests]
  );

  const statusColor = (status) => {
    switch (status) {
      case 'COMPLETED':
        return 'success';
      case 'PENDING':
        return 'warning';
      default:
        return 'info';
    }
  };

  return (
    <PanelLayout
      title="Compost & Circularity Hub"
      subtitle="Claim expired batches and close the loop on food waste."
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

      <Grid container spacing={3}>
        <Grid item xs={12} md={7}>
          <Paper elevation={0} sx={{ p: 3 }}>
            <Stack direction="row" justifyContent="space-between" alignItems="center" mb={2}>
              <div>
                <Typography variant="h6">Compost-ready batches</Typography>
                <Typography variant="body2" color="text.secondary">
                  Claim stale or surplus food before it reaches landfill.
                </Typography>
              </div>
              <Button size="small" onClick={loadAvailableDonations}>
                Refresh
              </Button>
            </Stack>
            <Grid container spacing={2}>
              {availableDonations.map((donation) => (
                <Grid item xs={12} key={donation.id}>
                  <Card variant="outlined">
                    <CardContent>
                      <Typography variant="h6">{donation.foodName}</Typography>
                      <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
                        {donation.description || 'No extra details provided.'}
                      </Typography>
                      <Stack direction="row" spacing={2}>
                        <Chip label={`${donation.quantity} kg equivalent`} size="small" />
                        <Chip label={dayjs(donation.expiryDate).format('MMM DD, HH:mm')} size="small" />
                      </Stack>
                    </CardContent>
                    <CardActions sx={{ justifyContent: 'flex-end', px: 3, pb: 2 }}>
                      <Button variant="contained" onClick={() => handleRequestDonation(donation.id)}>
                        Schedule pickup
                      </Button>
                    </CardActions>
                  </Card>
                </Grid>
              ))}
              {availableDonations.length === 0 && (
                <Grid item xs={12}>
                  <Typography variant="body2" color="text.secondary">
                    No compost batches waiting right now. Check back soon.
                  </Typography>
                </Grid>
              )}
            </Grid>
          </Paper>
        </Grid>

        <Grid item xs={12} md={5}>
          <Paper elevation={0} sx={{ p: 3 }}>
            <Stack direction="row" justifyContent="space-between" alignItems="center" mb={2}>
              <div>
                <Typography variant="h6">My pickups</Typography>
                <Typography variant="body2" color="text.secondary">
                  Update completion to reward eco impact points.
                </Typography>
              </div>
              <Button size="small" onClick={loadMyRequests}>
                Refresh
              </Button>
            </Stack>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Batch</TableCell>
                  <TableCell>Status</TableCell>
                  <TableCell align="right">Action</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {myRequests.map((request) => (
                  <TableRow key={request.id}>
                    <TableCell>
                      <Typography variant="subtitle2">{request.donation?.foodName}</Typography>
                      <Typography variant="caption" color="text.secondary">
                        {dayjs(request.createdAt).format('MMM DD, HH:mm')}
                      </Typography>
                    </TableCell>
                    <TableCell>
                      <Chip label={request.status} size="small" color={statusColor(request.status)} />
                    </TableCell>
                    <TableCell align="right">
                      {request.status !== 'COMPLETED' && (
                        <Button
                          size="small"
                          color="success"
                          variant="contained"
                          onClick={() => handleOpenCompostDialog(request)}
                        >
                          Upload proof & compost
                        </Button>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Paper>
        </Grid>
      </Grid>

      <Dialog
        open={compostDialog.open}
        onClose={() => !compostDialog.uploading && setCompostDialog((prev) => ({ ...prev, open: false }))}
        maxWidth="sm"
        fullWidth
      >
        <DialogTitle>Complete composting with proof</DialogTitle>
        <DialogContent dividers>
          <Stack spacing={2} mt={1}>
            <Input type="file" inputProps={{ accept: 'image/*' }} onChange={handleProofFileChange} fullWidth />
            <TextField
              label="Compost note (optional)"
              multiline
              minRows={3}
              value={compostDialog.note}
              onChange={(e) => setCompostDialog((prev) => ({ ...prev, note: e.target.value }))}
              placeholder="Composting method, batch reference, or completion details..."
              fullWidth
            />
            {compostDialog.imagePreview && (
              <Box
                component="img"
                src={compostDialog.imagePreview}
                alt="Compost proof preview"
                sx={{ width: '100%', maxHeight: 260, objectFit: 'cover', borderRadius: 1 }}
              />
            )}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setCompostDialog((prev) => ({ ...prev, open: false }))} disabled={compostDialog.uploading}>
            Cancel
          </Button>
          <Button variant="contained" color="success" onClick={handleMarkComposted} disabled={compostDialog.uploading}>
            Confirm composted
          </Button>
        </DialogActions>
      </Dialog>
    </PanelLayout>
  );
};

export default CompostPanel;

