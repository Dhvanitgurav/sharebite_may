package com.bitesharing.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserStatsDto {
    private Long userId;
    private String userName;
    private int totalActions;
    private int successfulActions;
    private double successRate;
    private int currentStreak;
    private int longestStreak;
    private int level;
    private int experiencePoints;
    private int experienceToNextLevel;
    private List<AchievementDto> achievements;
    private List<String> badges;
    private int rank;
    private double weeklyGoalProgress;
}