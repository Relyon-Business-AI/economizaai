package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.time.BrazilClock;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record HouseholdResponse(
        UUID id,
        String name,
        UUID hostId,
        String inviteCode,
        LocalDateTime inviteCodeExpiresAt,
        List<HouseholdMember> members,
        LocalDateTime createdAt
) {
    public record HouseholdMember(UUID id, String name, String email) {
        public static HouseholdMember from(User user) {
            return new HouseholdMember(user.getId(), user.getName(), user.getEmail());
        }
    }

    public static HouseholdResponse from(Household household, List<User> members) {
        return new HouseholdResponse(
                household.getId(),
                household.getName(),
                household.getOwner() != null ? household.getOwner().getId() : null,
                household.getInviteCode(),
                household.getInviteCodeExpiresAt(),
                members.stream().map(HouseholdMember::from).toList(),
                BrazilClock.fromUtc(household.getCreatedAt())
        );
    }
}
