package com.relyon.economizaai.dto.request;

import jakarta.validation.constraints.NotNull;

/** Toggle the caller's opt-in to the public "caçador de descontos" leaderboard. */
public record LeaderboardOptInRequest(@NotNull Boolean optIn) {
}
