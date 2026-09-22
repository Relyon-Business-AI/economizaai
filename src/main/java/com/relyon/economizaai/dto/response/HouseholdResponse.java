package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record HouseholdResponse(
        UUID id,
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
                household.getInviteCode(),
                household.getInviteCodeExpiresAt(),
                members.stream().map(HouseholdMember::from).toList(),
                household.getCreatedAt()
        );
    }
}
