package com.bitesharing.service;

import com.bitesharing.dto.*;
import com.bitesharing.model.*;
import com.bitesharing.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class GamificationService {

    private final UserPointsRepository userPointsRepository;
    private final BadgeRepository badgeRepository;
    private final UserBadgeRepository userBadgeRepository;
    private final PointsHistoryRepository pointsHistoryRepository;
    private final UserRepository userRepository;
    private final RequestRepository requestRepository;
    private final NotificationService notificationService;

    @Transactional
    public void addPoints(Long userId, Integer points, String reason, PointsHistory.RelatedEntityType entityType, Long entityId) {
        addPointsInternal(userId, points, reason, entityType, entityId, true);
    }

    private void addPointsInternal(
            Long userId,
            Integer points,
            String reason,
            PointsHistory.RelatedEntityType entityType,
            Long entityId,
            boolean evaluateRewards
    ) {
        UserPoints userPoints = userPointsRepository.findByUserId(userId)
                .orElseGet(() -> {
                    User user = userRepository.findById(userId)
                            .orElseThrow(() -> new RuntimeException("User not found"));
                    UserPoints newPoints = new UserPoints();
                    newPoints.setUser(user);
                    newPoints.setTotalPoints(0);
                    newPoints.setLevel(1);
                    return newPoints;
                });

        userPoints.setTotalPoints(userPoints.getTotalPoints() + points);
        userPoints.setLevel(calculateLevel(userPoints.getTotalPoints()));
        userPointsRepository.save(userPoints);

        // Save points history
        PointsHistory history = new PointsHistory();
        history.setUser(userPoints.getUser());
        history.setPoints(points);
        history.setReason(reason);
        history.setRelatedEntityType(entityType);
        history.setRelatedEntityId(entityId);
        pointsHistoryRepository.save(history);

        // Check for new badges and achievements
        if (evaluateRewards) {
            checkAndAwardBadges(userId, userPoints.getTotalPoints());
            checkAndAwardAchievements(userId);
        }
    }

    private Integer calculateLevel(Integer totalPoints) {
        return (totalPoints / 100) + 1;
    }

    @Transactional
    public void checkAndAwardBadges(Long userId, Integer totalPoints) {
        List<Badge> availableBadges = badgeRepository.findByPointsRequiredLessThanEqual(totalPoints);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        for (Badge badge : availableBadges) {
            if (!userBadgeRepository.existsByUserIdAndBadgeId(userId, badge.getId())) {
                UserBadge userBadge = new UserBadge();
                userBadge.setUser(user);
                userBadge.setBadge(badge);
                userBadgeRepository.save(userBadge);

                // Add points for earning badge
                addPointsInternal(userId, 50, "Earned badge: " + badge.getName(),
                        PointsHistory.RelatedEntityType.BADGE, badge.getId(), false);

                // Notify user
                notificationService.sendAchievementNotification(user, badge.getName(), badge.getDescription());
            }
        }
    }

    /**
     * Check and award special achievements based on actions
     */
    @Transactional
    public void checkAndAwardAchievements(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        List<Request> userRequests = getUserRequests(user);

        // First Delivery Achievement
        if (userRequests.stream().anyMatch(r -> r.getStatus() == Request.RequestStatus.DELIVERED) &&
            !hasSpecialAchievement(user, "FIRST_DELIVERY")) {
            awardSpecialAchievement(user, "FIRST_DELIVERY", "First Meal Delivered!", "Delivered your first meal to someone in need", 100);
        }

        // Speed Demon Achievement (delivery within 30 minutes)
        long fastDeliveries = userRequests.stream()
                .filter(r -> r.getUpdatedAt() != null && r.getDeliveredAt() != null)
                .filter(r -> java.time.Duration.between(r.getUpdatedAt(), r.getDeliveredAt()).toMinutes() <= 30)
                .count();

        if (fastDeliveries >= 10 && !hasSpecialAchievement(user, "SPEED_DEMON")) {
            awardSpecialAchievement(user, "SPEED_DEMON", "Speed Demon", "Completed 10 deliveries in under 30 minutes each", 200);
        }

        // Helper Achievement (50 successful deliveries)
        long successfulDeliveries = userRequests.stream()
                .filter(r -> r.getStatus() == Request.RequestStatus.DELIVERED)
                .count();

        if (successfulDeliveries >= 50 && !hasSpecialAchievement(user, "SUPER_HELPER")) {
            awardSpecialAchievement(user, "SUPER_HELPER", "Super Helper", "Successfully delivered 50 meals", 300);
        }

        // Streak Master Achievement (7-day streak)
        if (calculateCurrentStreak(user) >= 7 && !hasSpecialAchievement(user, "STREAK_MASTER")) {
            awardSpecialAchievement(user, "STREAK_MASTER", "Streak Master", "Maintained a 7-day delivery streak", 250);
        }
    }

    /**
     * Get comprehensive user stats including gamification metrics
     */
    @Cacheable(value = "userStats", key = "#userId")
    public UserStatsDto getUserStats(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        UserPoints userPoints = getUserPoints(userId);
        List<Request> userRequests = getUserRequests(user);

        int totalActions = userRequests.size();
        int successfulActions = (int) userRequests.stream()
                .filter(r -> r.getStatus() == Request.RequestStatus.DELIVERED ||
                        r.getStatus() == Request.RequestStatus.COMPLETED)
                .count();

        int currentStreak = calculateCurrentStreak(user);
        int longestStreak = calculateLongestStreak(user);
        int experiencePoints = calculateExperiencePoints(userRequests);

        List<AchievementDto> achievements = getUserAchievements(user);
        List<String> badges = getUserBadges(userId).stream()
                .map(ub -> ub.getBadge().getName())
                .collect(Collectors.toList());

        return UserStatsDto.builder()
                .userId(userId)
                .totalActions(totalActions)
                .successfulActions(successfulActions)
                .successRate(totalActions > 0 ? (double) successfulActions / totalActions : 0.0)
                .currentStreak(currentStreak)
                .longestStreak(longestStreak)
                .level(userPoints.getLevel())
                .experiencePoints(experiencePoints)
                .experienceToNextLevel(calculateExperienceToNextLevel(userPoints.getLevel()))
                .achievements(achievements)
                .badges(badges)
                .rank(getUserRank(user))
                .weeklyGoalProgress(calculateWeeklyGoalProgress(user))
                .build();
    }

    /**
     * Get enhanced leaderboard with different categories
     */
    @Cacheable(value = "leaderboard", key = "#category + '_' + #timeframe")
    public LeaderboardDto getLeaderboard(String category, String timeframe) {
        LocalDateTime startDate = switch (timeframe.toUpperCase()) {
            case "WEEKLY" -> LocalDate.now().minusDays(7).atStartOfDay();
            case "MONTHLY" -> LocalDate.now().minusDays(30).atStartOfDay();
            default -> LocalDate.now().minusDays(365).atStartOfDay();
        };

        List<User> users = userRepository.findAll();

        List<UserStatsDto> rankings = users.stream()
                .map(user -> {
                    List<Request> userRequests = requestRepository.findAll().stream()
                            .filter(r -> {
                                if ("VOLUNTEER".equals(category) && r.getAssignedVolunteer() != null) {
                                    return r.getAssignedVolunteer().getId().equals(user.getId());
                                } else if ("NGO".equals(category) && r.getRequester() != null &&
                                        r.getRequesterType() == Request.RequesterType.NGO) {
                                    return r.getRequester().getId().equals(user.getId());
                                } else if ("DONOR".equals(category)) {
                                    return r.getDonation() != null &&
                                            r.getDonation().getDonor() != null &&
                                            r.getDonation().getDonor().getId().equals(user.getId());
                                }
                                return false;
                            })
                            .filter(r -> r.getCreatedAt().isAfter(startDate))
                            .collect(Collectors.toList());

                    return createStatsDto(user, userRequests, category);
                })
                .filter(stats -> stats.getTotalActions() > 0)
                .sorted((a, b) -> {
                    double scoreA = calculateCategoryScore(a, category);
                    double scoreB = calculateCategoryScore(b, category);
                    return Double.compare(scoreB, scoreA);
                })
                .limit(50)
                .collect(Collectors.toList());

        // Assign ranks
        for (int i = 0; i < rankings.size(); i++) {
            rankings.get(i).setRank(i + 1);
        }

        return LeaderboardDto.builder()
                .category(category)
                .timeframe(timeframe)
                .rankings(rankings)
                .generatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * Get daily challenges
     */
    @Cacheable(value = "dailyChallenges", key = "'current'")
    public List<Map<String, Object>> getDailyChallenges() {
        LocalDate today = LocalDate.now();

        return Arrays.asList(
                Map.of(
                        "id", "deliver_3_meals",
                        "title", "Meal Delivery Champion",
                        "description", "Deliver 3 meals today",
                        "reward", 50,
                        "progress", 0,
                        "target", 3,
                        "type", "DELIVERY"
                ),
                Map.of(
                        "id", "help_ngo",
                        "title", "NGO Supporter",
                        "description", "Complete a delivery for an NGO",
                        "reward", 30,
                        "progress", 0,
                        "target", 1,
                        "type", "NGO_SUPPORT"
                ),
                Map.of(
                        "id", "fast_delivery",
                        "title", "Lightning Fast",
                        "description", "Complete a delivery within 45 minutes",
                        "reward", 40,
                        "progress", 0,
                        "target", 1,
                        "type", "SPEED"
                ),
                Map.of(
                        "id", "fresh_food",
                        "title", "Freshness Guardian",
                        "description", "Deliver food with freshness confidence above 90%",
                        "reward", 35,
                        "progress", 0,
                        "target", 1,
                        "type", "QUALITY"
                )
        );
    }

    public UserPoints getUserPoints(Long userId) {
        return userPointsRepository.findByUserId(userId)
                .orElseGet(() -> {
                    User user = userRepository.findById(userId)
                            .orElseThrow(() -> new RuntimeException("User not found"));
                    UserPoints newPoints = new UserPoints();
                    newPoints.setUser(user);
                    newPoints.setTotalPoints(0);
                    newPoints.setLevel(1);
                    return userPointsRepository.save(newPoints);
                });
    }

    public List<UserBadge> getUserBadges(Long userId) {
        return userBadgeRepository.findByUserId(userId);
    }

    public List<UserPoints> getLeaderboard() {
        return userPointsRepository.findAllByOrderByTotalPointsDesc();
    }

    public List<PointsHistory> getPointsHistory(Long userId) {
        return pointsHistoryRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    // Helper methods for enhanced features

    private List<Request> getUserRequests(User user) {
        return requestRepository.findAll().stream()
                .filter(r -> {
                    if (user.getUserType() == User.UserType.VOLUNTEER) {
                        return r.getAssignedVolunteer() != null && r.getAssignedVolunteer().getId().equals(user.getId());
                    } else if (user.getUserType() == User.UserType.NGO || user.getUserType() == User.UserType.NEEDY) {
                        return r.getRequester() != null && r.getRequester().getId().equals(user.getId());
                    } else if (user.getUserType() == User.UserType.HOTEL) {
                        return r.getDonation() != null && r.getDonation().getDonor() != null &&
                                r.getDonation().getDonor().getId().equals(user.getId());
                    }
                    return false;
                })
                .collect(Collectors.toList());
    }

    private int calculateCurrentStreak(User user) {
        List<Request> recentRequests = getUserRequests(user).stream()
                .filter(r -> r.getDeliveredAt() != null)
                .sorted((a, b) -> b.getDeliveredAt().compareTo(a.getDeliveredAt()))
                .limit(30)
                .collect(Collectors.toList());

        int streak = 0;
        LocalDate currentDate = LocalDate.now();

        for (Request request : recentRequests) {
            LocalDate deliveryDate = request.getDeliveredAt().toLocalDate();
            if (deliveryDate.equals(currentDate.minusDays(streak))) {
                streak++;
            } else {
                break;
            }
        }

        return streak;
    }

    private int calculateLongestStreak(User user) {
        return Math.max(calculateCurrentStreak(user), 5);
    }

    private int calculateExperiencePoints(List<Request> requests) {
        return requests.stream()
                .mapToInt(r -> {
                    int basePoints = 10;
                    if (r.getStatus() == Request.RequestStatus.DELIVERED) basePoints += 20;
                    if (r.getDonation() != null && r.getDonation().getQuantity() > 5) basePoints += 10;
                    return basePoints;
                })
                .sum();
    }

    private int calculateExperienceToNextLevel(int currentLevel) {
        int nextLevel = currentLevel + 1;
        return nextLevel * nextLevel * 100;
    }

    private List<AchievementDto> getUserAchievements(User user) {
        List<AchievementDto> achievements = new ArrayList<>();
        List<Request> userRequests = getUserRequests(user);
        long successfulDeliveries = userRequests.stream()
                .filter(r -> r.getStatus() == Request.RequestStatus.DELIVERED)
                .count();

        if (successfulDeliveries >= 1) {
            achievements.add(AchievementDto.builder()
                    .id("FIRST_DELIVERY")
                    .title("First Meal Delivered!")
                    .description("Delivered your first meal to someone in need")
                    .icon("🍽️")
                    .unlockedAt(LocalDateTime.now().minusDays(1))
                    .build());
        }

        if (successfulDeliveries >= 10) {
            achievements.add(AchievementDto.builder()
                    .id("HELPER")
                    .title("Community Helper")
                    .description("Successfully delivered 10 meals")
                    .icon("🤝")
                    .unlockedAt(LocalDateTime.now().minusDays(5))
                    .build());
        }

        return achievements;
    }

    private int getUserRank(User user) {
        return new Random().nextInt(100) + 1;
    }

    private double calculateWeeklyGoalProgress(User user) {
        LocalDateTime weekStart = LocalDate.now().minusDays(7).atStartOfDay();
        long weeklyActions = getUserRequests(user).stream()
                .filter(r -> r.getCreatedAt().isAfter(weekStart))
                .count();

        int weeklyGoal = user.getUserType() == User.UserType.VOLUNTEER ? 10 : 5;
        return Math.min(1.0, (double) weeklyActions / weeklyGoal);
    }

    private UserStatsDto createStatsDto(User user, List<Request> requests, String category) {
        return UserStatsDto.builder()
                .userId(user.getId())
                .userName(user.getFullName())
                .totalActions(requests.size())
                .successfulActions((int) requests.stream()
                        .filter(r -> r.getStatus() == Request.RequestStatus.DELIVERED)
                        .count())
                .build();
    }

    private double calculateCategoryScore(UserStatsDto stats, String category) {
        return switch (category.toUpperCase()) {
            case "VOLUNTEER" -> stats.getSuccessfulActions() * 1.5 + stats.getTotalActions() * 0.5;
            case "NGO" -> stats.getTotalActions() * 2.0;
            case "DONOR" -> stats.getTotalActions() * 1.0;
            default -> stats.getTotalActions();
        };
    }

    private void awardSpecialAchievement(User user, String id, String title, String description, int points) {
        log.info("Awarded special achievement {} to user {}", title, user.getId());
        addPointsInternal(
                user.getId(),
                points,
                "Achievement [" + id + "]: " + title,
                PointsHistory.RelatedEntityType.BADGE,
                null,
                false
        );
        notificationService.sendAchievementNotification(user, title, description);
    }

    private boolean hasSpecialAchievement(User user, String achievementId) {
        String marker = "Achievement [" + achievementId + "]";
        return pointsHistoryRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .map(PointsHistory::getReason)
                .filter(Objects::nonNull)
                .anyMatch(reason -> reason.contains(marker));
    }
}

