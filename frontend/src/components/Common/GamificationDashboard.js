import React, { useState, useEffect } from 'react';
import {
    Box, Card, CardContent, Typography, Grid, LinearProgress,
    Chip, Avatar, Badge, List, ListItem, ListItemAvatar, ListItemText,
    Divider, Tabs, Tab, Paper, Button
} from '@mui/material';
import {
    EmojiEvents, Star, TrendingUp, LocalShipping,
    Timeline, Whatshot, Grade, Refresh
} from '@mui/icons-material';
import { useAuth } from '../../context/AuthContext';
import api from '../../utils/api';

const GamificationDashboard = () => {
    const { user } = useAuth();
    const [userStats, setUserStats] = useState(null);
    const [leaderboard, setLeaderboard] = useState(null);
    const [challenges, setChallenges] = useState([]);
    const [activeTab, setActiveTab] = useState(0);
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        if (user?.id) {
            loadUserData();
        }
    }, [user]);

    const loadUserData = async () => {
        try {
            setLoading(true);
            const [statsRes, leaderboardRes, challengesRes] = await Promise.all([
                api.get(`/analytics/user/${user.id}/stats`),
                api.get('/analytics/leaderboard/VOLUNTEER/WEEKLY'),
                api.get('/analytics/challenges/daily')
            ]);

            setUserStats(statsRes.data);
            setLeaderboard(leaderboardRes.data);
            setChallenges(challengesRes.data);
        } catch (err) {
            console.error('Failed to load user data:', err);
        } finally {
            setLoading(false);
        }
    };

    const getPerformanceColor = (level) => {
        switch (level?.toUpperCase()) {
            case 'EXCELLENT': return 'success';
            case 'VERY_GOOD': return 'primary';
            case 'GOOD': return 'info';
            case 'SATISFACTORY': return 'warning';
            default: return 'error';
        }
    };

    const getLevelProgress = () => {
        if (!userStats) return 0;
        const currentXP = userStats.experiencePoints;
        const nextLevelXP = userStats.experiencePoints + userStats.experienceToNextLevel;
        return (currentXP / nextLevelXP) * 100;
    };

    if (loading) {
        return (
            <Box sx={{ p: 3 }}>
                <LinearProgress />
                <Typography sx={{ mt: 2 }}>Loading your achievements...</Typography>
            </Box>
        );
    }

    return (
        <Box sx={{ p: 3 }}>
            <Typography variant="h4" gutterBottom sx={{ display: 'flex', alignItems: 'center', mb: 3 }}>
                🏆 My Achievements & Stats
            </Typography>

            <Tabs value={activeTab} onChange={(e, newValue) => setActiveTab(newValue)} sx={{ mb: 3 }}>
                <Tab label="Overview" />
                <Tab label="Achievements" />
                <Tab label="Leaderboard" />
                <Tab label="Challenges" />
            </Tabs>

            {/* Overview Tab */}
            {activeTab === 0 && (
                <Grid container spacing={3}>
                    <Grid item xs={12} md={8}>
                        <Card sx={{ mb: 3 }}>
                            <CardContent>
                                <Typography variant="h6" gutterBottom>Level Progress</Typography>
                                <Box sx={{ display: 'flex', alignItems: 'center', mb: 2 }}>
                                    <Typography variant="h4" sx={{ mr: 2 }}>
                                        Level {userStats?.level || 1}
                                    </Typography>
                                    <Chip
                                        label={userStats?.performanceLevel || 'GOOD'}
                                        color={getPerformanceColor(userStats?.performanceLevel)}
                                        size="small"
                                    />
                                </Box>
                                <LinearProgress
                                    variant="determinate"
                                    value={getLevelProgress()}
                                    sx={{ height: 10, borderRadius: 5, mb: 1 }}
                                />
                                <Typography variant="body2" color="text.secondary">
                                    {userStats?.experiencePoints || 0} / {(userStats?.experiencePoints || 0) + (userStats?.experienceToNextLevel || 100)} XP
                                </Typography>
                            </CardContent>
                        </Card>

                        <Grid container spacing={2}>
                            <Grid item xs={6} sm={3}>
                                <Card>
                                    <CardContent sx={{ textAlign: 'center' }}>
                                        <LocalShipping sx={{ fontSize: 40, color: 'primary.main', mb: 1 }} />
                                        <Typography variant="h4">{userStats?.totalActions || 0}</Typography>
                                        <Typography variant="body2" color="text.secondary">Total Actions</Typography>
                                    </CardContent>
                                </Card>
                            </Grid>
                            <Grid item xs={6} sm={3}>
                                <Card>
                                    <CardContent sx={{ textAlign: 'center' }}>
                                        <Star sx={{ fontSize: 40, color: 'success.main', mb: 1 }} />
                                        <Typography variant="h4">{userStats?.successfulActions || 0}</Typography>
                                        <Typography variant="body2" color="text.secondary">Success Rate</Typography>
                                        <Typography variant="caption" color="success.main">
                                            {((userStats?.successRate || 0) * 100).toFixed(1)}%
                                        </Typography>
                                    </CardContent>
                                </Card>
                            </Grid>
                            <Grid item xs={6} sm={3}>
                                <Card>
                                    <CardContent sx={{ textAlign: 'center' }}>
                                        <Whatshot sx={{ fontSize: 40, color: 'warning.main', mb: 1 }} />
                                        <Typography variant="h4">{userStats?.currentStreak || 0}</Typography>
                                        <Typography variant="body2" color="text.secondary">Current Streak</Typography>
                                    </CardContent>
                                </Card>
                            </Grid>
                            <Grid item xs={6} sm={3}>
                                <Card>
                                    <CardContent sx={{ textAlign: 'center' }}>
                                        <Grade sx={{ fontSize: 40, color: 'secondary.main', mb: 1 }} />
                                        <Typography variant="h4">#{userStats?.rank || 'N/A'}</Typography>
                                        <Typography variant="body2" color="text.secondary">Your Rank</Typography>
                                    </CardContent>
                                </Card>
                            </Grid>
                        </Grid>
                    </Grid>

                    <Grid item xs={12} md={4}>
                        <Card>
                            <CardContent>
                                <Typography variant="h6" gutterBottom>Weekly Goal Progress</Typography>
                                <Box sx={{ position: 'relative', mb: 2 }}>
                                    <LinearProgress
                                        variant="determinate"
                                        value={(userStats?.weeklyGoalProgress || 0) * 100}
                                        sx={{ height: 20, borderRadius: 10 }}
                                    />
                                    <Typography
                                        sx={{
                                            position: 'absolute',
                                            top: '50%',
                                            left: '50%',
                                            transform: 'translate(-50%, -50%)',
                                            fontSize: '0.75rem',
                                            fontWeight: 'bold'
                                        }}
                                    >
                                        {((userStats?.weeklyGoalProgress || 0) * 100).toFixed(0)}%
                                    </Typography>
                                </Box>
                                <Typography variant="body2" color="text.secondary">
                                    Keep up the great work! You're making a real difference in your community.
                                </Typography>
                            </CardContent>
                        </Card>
                    </Grid>
                </Grid>
            )}

            {/* Achievements Tab */}
            {activeTab === 1 && (
                <Grid container spacing={3}>
                    <Grid item xs={12}>
                        <Card>
                            <CardContent>
                                <Typography variant="h6" gutterBottom sx={{ display: 'flex', alignItems: 'center' }}>
                                    <EmojiEvents sx={{ mr: 1 }} />
                                    Your Achievements
                                </Typography>
                                <Grid container spacing={2}>
                                    {userStats?.achievements?.map((achievement) => (
                                        <Grid item xs={12} sm={6} md={4} key={achievement.id}>
                                            <Card sx={{ border: '2px solid gold' }}>
                                                <CardContent sx={{ textAlign: 'center' }}>
                                                    <Typography variant="h6" sx={{ mb: 1 }}>
                                                        {achievement.icon} {achievement.title}
                                                    </Typography>
                                                    <Typography variant="body2" color="text.secondary">
                                                        {achievement.description}
                                                    </Typography>
                                                    <Typography variant="caption" sx={{ mt: 1, display: 'block' }}>
                                                        Unlocked: {new Date(achievement.unlockedAt).toLocaleDateString()}
                                                    </Typography>
                                                </CardContent>
                                            </Card>
                                        </Grid>
                                    ))}
                                </Grid>
                            </CardContent>
                        </Card>
                    </Grid>

                    <Grid item xs={12}>
                        <Card>
                            <CardContent>
                                <Typography variant="h6" gutterBottom>Badges Earned</Typography>
                                <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1 }}>
                                    {userStats?.badges?.map((badge, index) => (
                                        <Chip
                                            key={index}
                                            label={badge}
                                            color="primary"
                                            variant="outlined"
                                            avatar={<Avatar>🏆</Avatar>}
                                        />
                                    ))}
                                </Box>
                            </CardContent>
                        </Card>
                    </Grid>
                </Grid>
            )}

            {/* Leaderboard Tab */}
            {activeTab === 2 && (
                <Card>
                    <CardContent>
                        <Typography variant="h6" gutterBottom sx={{ display: 'flex', alignItems: 'center' }}>
                            <TrendingUp sx={{ mr: 1 }} />
                            Weekly Leaderboard - Volunteers
                        </Typography>
                        <List>
                            {leaderboard?.rankings?.map((volunteer, index) => (
                                <React.Fragment key={volunteer.userId}>
                                    <ListItem sx={{
                                        bgcolor: volunteer.userId === user?.id ? 'action.selected' : 'inherit'
                                    }}>
                                        <ListItemAvatar>
                                            <Badge
                                                badgeContent={index + 1}
                                                color={index === 0 ? 'default' : index === 1 ? 'primary' : 'secondary'}
                                            >
                                                <Avatar sx={{
                                                    bgcolor: index === 0 ? 'gold' : index === 1 ? 'silver' : index === 2 ? '#cd7f32' : 'grey.300'
                                                }}>
                                                    {volunteer.userName?.charAt(0)?.toUpperCase() || 'V'}
                                                </Avatar>
                                            </Badge>
                                        </ListItemAvatar>
                                        <ListItemText
                                            primary={
                                                <Box sx={{ display: 'flex', alignItems: 'center' }}>
                                                    <Typography variant="subtitle1">
                                                        {volunteer.userName || `Volunteer ${volunteer.userId}`}
                                                    </Typography>
                                                    {volunteer.userId === user?.id && (
                                                        <Chip label="You" size="small" color="primary" sx={{ ml: 1 }} />
                                                    )}
                                                </Box>
                                            }
                                            secondary={`${volunteer.successfulActions} successful deliveries • ${volunteer.totalActions} total actions`}
                                        />
                                        <Box sx={{ textAlign: 'right' }}>
                                            <Typography variant="h6" color="primary">
                                                Rank #{index + 1}
                                            </Typography>
                                            <Typography variant="body2" color="text.secondary">
                                                {((volunteer.successRate || 0) * 100).toFixed(1)}% success rate
                                            </Typography>
                                        </Box>
                                    </ListItem>
                                    {index < (leaderboard.rankings.length - 1) && <Divider />}
                                </React.Fragment>
                            ))}
                        </List>
                    </CardContent>
                </Card>
            )}

            {/* Challenges Tab */}
            {activeTab === 3 && (
                <Grid container spacing={3}>
                    <Grid item xs={12}>
                        <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 2 }}>
                            <Typography variant="h6">Daily Challenges</Typography>
                            <Button
                                startIcon={<Refresh />}
                                onClick={loadUserData}
                                variant="outlined"
                                size="small"
                            >
                                Refresh
                            </Button>
                        </Box>
                    </Grid>

                    {challenges.map((challenge, index) => (
                        <Grid item xs={12} md={6} key={challenge.id}>
                            <Card>
                                <CardContent>
                                    <Typography variant="h6" gutterBottom>
                                        {challenge.title}
                                    </Typography>
                                    <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
                                        {challenge.description}
                                    </Typography>

                                    <Box sx={{ mb: 2 }}>
                                        <Typography variant="body2" gutterBottom>
                                            Progress: {challenge.progress || 0} / {challenge.target}
                                        </Typography>
                                        <LinearProgress
                                            variant="determinate"
                                            value={((challenge.progress || 0) / challenge.target) * 100}
                                            sx={{ height: 8, borderRadius: 4 }}
                                        />
                                    </Box>

                                    <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                                        <Chip
                                            label={`+${challenge.reward} XP`}
                                            color="success"
                                            size="small"
                                        />
                                        <Typography variant="body2" color="primary">
                                            {challenge.type.replace('_', ' ')}
                                        </Typography>
                                    </Box>
                                </CardContent>
                            </Card>
                        </Grid>
                    ))}
                </Grid>
            )}
        </Box>
    );
};

export default GamificationDashboard;