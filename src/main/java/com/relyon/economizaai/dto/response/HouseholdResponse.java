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
        LocalDateTime createdAt,
        boolean canLeave
) {
    /** {@code joinedAt} is null for the household's original host (they never joined — it's their home). */
    public record HouseholdMember(UUID id, String name, String email, LocalDateTime joinedAt) {
        public static HouseholdMember from(User user) {
            return new HouseholdMember(user.getId(), user.getName(), user.getEmail(),
                    user.getHouseholdJoinedAt() == null ? null : BrazilClock.fromUtc(user.getHouseholdJoinedAt()));
        }
    }

    /** Viewer-agnostic view (LGPD export etc.): invite code included, canLeave = has company. */
    public static HouseholdResponse from(Household household, List<User> members) {
        return build(household, members, household.getInviteCode(), members.size() > 1);
    }

    /**
     * The household as {@code viewer} sees it: only the host gets the invite code (only the
     * host may invite), and {@code canLeave} says whether the leave button applies — false
     * for someone alone in their own home (there is nowhere to go).
     */
    public static HouseholdResponse forViewer(Household household, List<User> members, User viewer) {
        var hostId = household.getOwner() != null ? household.getOwner().getId() : null;
        var viewerIsHost = viewer.getId().equals(hostId);
        var awayFromHome = viewer.getHomeHousehold() != null
                && !viewer.getHomeHousehold().getId().equals(household.getId());
        return build(household, members, viewerIsHost ? household.getInviteCode() : null,
                members.size() > 1 || awayFromHome);
    }

    private static HouseholdResponse build(Household household, List<User> members, String inviteCode, boolean canLeave) {
        return new HouseholdResponse(
                household.getId(),
                household.getName(),
                household.getOwner() != null ? household.getOwner().getId() : null,
                inviteCode,
                inviteCode == null || household.getInviteCodeExpiresAt() == null
                        ? null : BrazilClock.fromUtc(household.getInviteCodeExpiresAt()),
                members.stream().map(HouseholdMember::from).toList(),
                BrazilClock.fromUtc(household.getCreatedAt()),
                canLeave
        );
    }
}
