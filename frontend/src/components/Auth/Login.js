import React, { useState } from 'react';
import {
  Box,
  Container,
  Paper,
  Typography,
  TextField,
  Stack,
  InputAdornment,
  IconButton,
  Link,
} from '@mui/material';
import { LoadingButton } from '@mui/lab';
import { Visibility, VisibilityOff, Login as LoginIcon } from '@mui/icons-material';
import { useNavigate } from 'react-router-dom';
import { useSnackbar } from 'notistack';
import { useAuth } from '../../context/AuthContext';
import api from '../../utils/api';
import ShareBiteHelpChat from '../Common/ShareBiteHelpChat';

const roleRoutes = {
  ADMIN: '/admin',
  HOTEL: '/hotel',
  NGO: '/ngo',
  VOLUNTEER: '/volunteer',
  NEEDY: '/needy',
  COMPOST_AGENCY: '/compost',
};

const Login = () => {
  const [formData, setFormData] = useState({ username: '', password: '' });
  const [showPassword, setShowPassword] = useState(false);
  const [loading, setLoading] = useState(false);
  const { login } = useAuth();
  const { enqueueSnackbar } = useSnackbar();
  const navigate = useNavigate();

  const handleSubmit = async (e) => {
    e.preventDefault();
    setLoading(true);
    try {
      const response = await api.post('/auth/login', formData);
      const { token, username, userType, userId, id } = response.data;
      const resolvedUserId = userId ?? id;
      
      // Update auth context (this also sets localStorage)
      login(token, username, userType, resolvedUserId);
      
      enqueueSnackbar('Welcome back! Redirecting to your dashboard.', { variant: 'success' });
      navigate(roleRoutes[userType] || '/login', { replace: true });
    } catch (err) {
      console.error('Login error:', err);
      const errorMessage = err.response?.data?.message || 
                          err.response?.data?.error || 
                          'Login failed. Please check your credentials.';
      enqueueSnackbar(errorMessage, { variant: 'error' });
    } finally {
      setLoading(false);
    }
  };

  return (
    <Box
      sx={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: 'radial-gradient(circle at top, #d7f5dd, #edf1f7)',
        py: 6,
      }}
    >
      <Container maxWidth="sm">
        <Paper elevation={0} sx={{ p: { xs: 4, md: 6 }, borderRadius: 5 }}>
          <Stack spacing={2} textAlign="center" mb={3}>
            <Typography
              variant="h3"
              sx={{
                fontWeight: 900,
                background: 'linear-gradient(45deg,#2E7D32,#00ACC1)',
                backgroundClip: 'text',
                WebkitTextFillColor: 'transparent',
              }}
            >
              ShareBite
            </Typography>
            <Typography variant="overline" color="primary">
              Zero Food Waste Initiative
            </Typography>
            <Typography variant="h4" fontWeight={700}>
              Sign in to ShareBite
            </Typography>
            <Typography variant="body2" color="text.secondary">
              Manage donations, pickups, and reports in a single, real-time dashboard.
            </Typography>
          </Stack>
          <Box component="form" onSubmit={handleSubmit}>
            <Stack spacing={3}>
              <TextField
                label="Username"
                value={formData.username}
                onChange={(e) => setFormData({ ...formData, username: e.target.value })}
                required
                fullWidth
              />
              <TextField
                label="Password"
                type={showPassword ? 'text' : 'password'}
                value={formData.password}
                onChange={(e) => setFormData({ ...formData, password: e.target.value })}
                required
                fullWidth
                InputProps={{
                  endAdornment: (
                    <InputAdornment position="end">
                      <IconButton onClick={() => setShowPassword((prev) => !prev)} edge="end">
                        {showPassword ? <VisibilityOff /> : <Visibility />}
                      </IconButton>
                    </InputAdornment>
                  ),
                }}
              />
              <LoadingButton
                type="submit"
                size="large"
                variant="contained"
                loading={loading}
                startIcon={<LoginIcon />}
                sx={{ borderRadius: 2 }}
              >
                Sign in
              </LoadingButton>
            </Stack>
          </Box>
          <Stack direction="row" spacing={1} justifyContent="center" mt={4}>
            <Typography variant="body2">New to ShareBite?</Typography>
            <Link component="button" variant="body2" onClick={() => navigate('/register')}>
              Create an account
            </Link>
          </Stack>
        </Paper>
      </Container>
      <ShareBiteHelpChat />
    </Box>
  );
};

export default Login;

