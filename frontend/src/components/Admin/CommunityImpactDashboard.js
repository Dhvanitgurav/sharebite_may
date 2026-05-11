import React, { useState, useEffect } from 'react';
import {
    Box, Card, CardContent, Typography, Grid, LinearProgress,
    Chip, Avatar, List, ListItem, ListItemAvatar, ListItemText,
    Divider, Alert, Paper
} from '@mui/material';
import {
    Restaurant, Recycling, People, Nature, TrendingUp,
    EmojiEvents, LocalShipping, AccessTime
} from '@mui/icons-material';
import api from '../../utils/api';

const CommunityImpactDashboard = () => {
    const [impactData, setImpactData] = useState(null);
    const [wasteAnalytics, setWasteAnalytics] = useState(null);
    const [leaderboard, setLeaderboard] = useState(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(null);

    useEffect(() => {
        loadDashboardData();
    }, []);

    const loadDashboardData = async () => {
        try {
            setLoading(true);
            const [impactRes, wasteRes, leaderboardRes] = await Promise.all([
                api.get('/analytics/community-impact'),
                api.get('/analytics/waste-analytics'),
                api.get('/analytics/leaderboard/VOLUNTEER/WEEKLY')
            ]);

            setImpactData(impactRes.data);
            setWasteAnalytics(wasteRes.data);
            setLeaderboardRes(leaderboardRes.data);
        } catch (err) {
            setError('Failed to load dashboard data');
            console.error('Dashboard error:', err);
        } finally {
            setLoading(false);
        }
    };

    if (loading) {
        return (
            <Box sx={{ p: 3 }}>
                <LinearProgress />
                <Typography sx={{ mt: 2 }}>Loading community impact data...</Typography>
            </Box>
        );
    }

    if (error) {
        return (
            <Box sx={{ p: 3 }}>
                <Alert severity="error">{error}</Alert>
            </Box>
        );
    }

    return (
        <Box sx={{ p: 3 }}>
            <Typography variant="h4" gutterBottom sx={{ display: 'flex', alignItems: 'center', mb: 3 }}>
                🌍 Community Impact Dashboard
            </Typography>

            {/* Key Metrics Cards */}
            <Grid container spacing={3} sx={{ mb: 4 }}>
                <Grid item xs={12} sm={6} md={3}>
                    <Card sx={{ background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)', color: 'white' }}>
                        <CardContent>
                            <Box sx={{ display: 'flex', alignItems: 'center', mb: 1 }}>
                                <Restaurant sx={{ mr: 1 }} />
                                <Typography variant="h6">Meals Served Today</Typography>
                            </Box>
                            <Typography variant="h3" sx={{ fontWeight: 'bold' }}>
                                {impactData?.mealsServedToday || 0}
                            </Typography>
                        </CardContent>
                    </Card>
                </Grid>

                <Grid item xs={12} sm={6} md={3}>
                    <Card sx={{ background: 'linear-gradient(135deg, #f093fb 0%, #f5576c 100%)', color: 'white' }}>
                        <CardContent>
                            <Box sx={{ display: 'flex', alignItems: 'center', mb: 1 }}>
                                <Nature sx={{ mr: 1 }} />
                                <Typography variant="h6">CO₂ Saved (kg)</Typography>
                            </Box>
                            <Typography variant="h3" sx={{ fontWeight: 'bold' }}>
                                {impactData?.carbonFootprintSavedKg?.toFixed(1) || 0}
                            </Typography>
                        </CardContent>
                    </Card>
                </Grid>

                <Grid item xs={12} sm={6} md={3}>
                    <Card sx={{ background: 'linear-gradient(135deg, #4facfe 0%, #00f2fe 100%)', color: 'white' }}>
                        <CardContent>
                            <Box sx={{ display: 'flex', alignItems: 'center', mb: 1 }}>
                                <Recycling sx={{ mr: 1 }} />
                                <Typography variant="h6">Waste Prevented (kg)</Typography>
                            </Box>
                            <Typography variant="h3" sx={{ fontWeight: 'bold' }}>
                                {impactData?.foodWastePreventedKg || 0}
                            </Typography>
                        </CardContent>
                    </Card>
                </Grid>

                <Grid item xs={12} sm={6} md={3}>
                    <Card sx={{ background: 'linear-gradient(135deg, #43e97b 0%, #38f9d7 100%)', color: 'white' }}>
                        <CardContent>
                            <Box sx={{ display: 'flex', alignItems: 'center', mb: 1 }}>
                                <TrendingUp sx={{ mr: 1 }} />
                                <Typography variant="h6">Impact Score</Typography>
                            </Box>
                            <Typography variant="h3" sx={{ fontWeight: 'bold' }}>
                                {impactData?.impactScore?.toFixed(1) || 0}
                            </Typography>
                        </CardContent>
                    </Card>
                </Grid>
            </Grid>

            {/* Community Stats */}
            <Grid container spacing={3} sx={{ mb: 4 }}>
                <Grid item xs={12} md={6}>
                    <Card>
                        <CardContent>
                            <Typography variant="h6" gutterBottom sx={{ display: 'flex', alignItems: 'center' }}>
                                <People sx={{ mr: 1 }} />
                                Community Engagement
                            </Typography>
                            <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 2 }}>
                                <Box>
                                    <Typography variant="h4" color="primary">
                                        {impactData?.activeVolunteers || 0}
                                    </Typography>
                                    <Typography variant="body2" color="text.secondary">
                                        Active Volunteers
                                    </Typography>
                                </Box>
                                <Box>
                                    <Typography variant="h4" color="secondary">
                                        {impactData?.activeNgos || 0}
                                    </Typography>
                                    <Typography variant="body2" color="text.secondary">
                                        Active NGOs
                                    </Typography>
                                </Box>
                            </Box>
                        </CardContent>
                    </Card>
                </Grid>

                <Grid item xs={12} md={6}>
                    <Card>
                        <CardContent>
                            <Typography variant="h6" gutterBottom>
                                Waste Prevention Insights
                            </Typography>
                            <Box sx={{ mb: 2 }}>
                                <Typography variant="body2" color="text.secondary" gutterBottom>
                                    Prevention Rate: {(wasteAnalytics?.wastePreventionRate * 100)?.toFixed(1) || 0}%
                                </Typography>
                                <LinearProgress
                                    variant="determinate"
                                    value={wasteAnalytics?.wastePreventionRate * 100 || 0}
                                    sx={{ height: 8, borderRadius: 4 }}
                                />
                            </Box>
                            <Typography variant="body2" color="warning.main">
                                🚨 {wasteAnalytics?.highRiskDonationsCount || 0} donations expiring soon
                            </Typography>
                        </CardContent>
                    </Card>
                </Grid>
            </Grid>

            {/* Top Performers */}
            <Grid container spacing={3}>
                <Grid item xs={12} md={6}>
                    <Card>
                        <CardContent>
                            <Typography variant="h6" gutterBottom sx={{ display: 'flex', alignItems: 'center' }}>
                                <EmojiEvents sx={{ mr: 1 }} />
                                Top Volunteers This Week
                            </Typography>
                            <List>
                                {leaderboard?.rankings?.slice(0, 5).map((volunteer, index) => (
                                    <React.Fragment key={volunteer.userId}>
                                        <ListItem>
                                            <ListItemAvatar>
                                                <Avatar sx={{
                                                    bgcolor: index === 0 ? 'gold' : index === 1 ? 'silver' : index === 2 ? '#cd7f32' : 'grey.300'
                                                }}>
                                                    {index + 1}
                                                </Avatar>
                                            </ListItemAvatar>
                                            <ListItemText
                                                primary={volunteer.userName || `Volunteer ${volunteer.userId}`}
                                                secondary={`${volunteer.successfulActions} successful deliveries`}
                                            />
                                            <Chip
                                                label={`${volunteer.totalActions} total`}
                                                size="small"
                                                color="primary"
                                            />
                                        </ListItem>
                                        {index < 4 && <Divider />}
                                    </React.Fragment>
                                ))}
                            </List>
                        </CardContent>
                    </Card>
                </Grid>

                <Grid item xs={12} md={6}>
                    <Card>
                        <CardContent>
                            <Typography variant="h6" gutterBottom>
                                Recommendations
                            </Typography>
                            <List>
                                <ListItem>
                                    <ListItemAvatar>
                                        <AccessTime />
                                    </ListItemAvatar>
                                    <ListItemText
                                        primary="Increase volunteer capacity during peak hours"
                                        secondary="Based on donation patterns analysis"
                                    />
                                </ListItem>
                                <ListItem>
                                    <ListItemAvatar>
                                        <LocalShipping />
                                    </ListItemAvatar>
                                    <ListItemText
                                        primary="Partner with more NGOs for better distribution"
                                        secondary="Expand reach to serve more communities"
                                    />
                                </ListItem>
                                <ListItem>
                                    <ListItemAvatar>
                                        <Nature />
                                    </ListItemAvatar>
                                    <ListItemText
                                        primary="Focus on perishable food types"
                                        secondary="Higher waste prevention impact"
                                    />
                                </ListItem>
                            </List>
                        </CardContent>
                    </Card>
                </Grid>
            </Grid>

            {/* Emergency Mode Status */}
            <Paper sx={{ p: 2, mt: 3, backgroundColor: '#fff3e0' }}>
                <Typography variant="h6" color="warning.main" gutterBottom>
                    🚨 Emergency Response Ready
                </Typography>
                <Typography variant="body2">
                    System is configured for disaster response scenarios. Emergency mode can be activated
                    to prioritize food distribution to affected areas.
                </Typography>
            </Paper>
        </Box>
    );
};

export default CommunityImpactDashboard;