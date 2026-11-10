package com.relyon.economizaai.service;

import com.relyon.economizaai.dto.request.JoinHouseholdRequest;
import com.relyon.economizaai.exception.InvalidInviteCodeException;
import com.relyon.economizaai.exception.NotHouseholdHostException;
import com.relyon.economizaai.exception.NotInHouseholdException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.HouseholdRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.cache.HouseholdCacheGen;
import com.relyon.economizaai.service.notifications.NotificationPayload;
import com.relyon.economizaai.service.notifications.NotificationService;
import com.relyon.economizaai.service.subscription.SubscriptionGateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the HouseholdService branches not exercised by {@link HouseholdServiceTest}:
 * getMine/regenerate not-found, host-only regenerate, removeMember (all paths, restore +
 * copy on kick), expired invite code.
 */
@ExtendWith(MockitoExtension.class)
class HouseholdServiceCoverageTest {

    @Mock private HouseholdRepository householdRepository;
    @Mock private UserRepository userRepository;
    @Mock private ReceiptRepository receiptRepository;
    @Mock private HouseholdMergeService mergeService;
    @Mock private HouseholdCacheGen householdCacheGen;
    @Mock private SubscriptionGateService subscriptionGate;
    @Mock private NotificationService notificationService;
    @Mock private LocalizedMessageService messageService;

    @InjectMocks private HouseholdService householdService;

    private static final LocalDateTime LONG_AGO = LocalDateTime.of(2026, 1, 1, 12, 0);

    @BeforeEach
    void setUp() {
        lenient().when(messageService.translate(anyString(), any(Locale.class), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(householdRepository.save(any(Household.class))).thenAnswer(invocation -> {
            var saved = invocation.<Household>getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
                saved.setCreatedAt(LocalDateTime.now());
            }
            return saved;
        });
        lenient().when(householdRepository.existsByInviteCode(any())).thenReturn(false);
        lenient().when(mergeService.restoreOriginals(any())).thenReturn(new HouseholdMergeService.MergeResult(0, 0));
        lenient().when(mergeService.copyGeneratedSince(any(), any(), any())).thenReturn(0);
    }

    private Household household(String code) {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode(code).build();
        household.setCreatedAt(LocalDateTime.now());
        household.setUpdatedAt(LocalDateTime.now());
        return household;
    }

    private User user(String name, Household current, Household origin, LocalDateTime joinedAt) {
        var user = User.builder()
                .id(UUID.randomUUID())
                .name(name)
                .email(name.toLowerCase() + "@test.com")
                .password("encoded")
                .household(current)
                .originHousehold(origin)
                .householdJoinedAt(joinedAt)
                .build();
        user.setCreatedAt(joinedAt);
        user.setUpdatedAt(joinedAt);
        return user;
    }

    private User hostOf(Household home, String name) {
        var host = user(name, home, home, LONG_AGO);
        home.setOwner(host);
        return host;
    }

    private void members(Household household, User... members) {
        when(userRepository.findAllByHouseholdId(household.getId())).thenReturn(List.of(members));
    }

    // ---------- getMine ----------

    @Test
    void getMine_throwsWhenHouseholdMissing() {
        var home = household("ABC123");
        var user = hostOf(home, "Ana");
        when(householdRepository.findById(home.getId())).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> householdService.getMine(user));
    }

    // ---------- regenerateInviteCode ----------

    @Test
    void regenerateInviteCode_hostRotatesCodeAndReturnsMembers() {
        var home = household("OLD999");
        var host = hostOf(home, "Ana");
        when(householdRepository.findById(home.getId())).thenReturn(Optional.of(home));
        members(home, host);

        var response = householdService.regenerateInviteCode(host);

        assertNotNull(response.inviteCode());
        assertEquals(6, response.inviteCode().length());
        assertNotEquals("OLD999", response.inviteCode());
        assertNotNull(home.getInviteCodeExpiresAt());
        verify(householdRepository).save(home);
    }

    @Test
    void regenerateInviteCode_guestIsForbidden() {
        var home = household("OLD999");
        var host = hostOf(home, "Ana");
        var guest = user("Bruno", home, household("BRU001"), LocalDateTime.now());
        when(householdRepository.findById(home.getId())).thenReturn(Optional.of(home));
        members(home, host, guest);

        assertThrows(NotHouseholdHostException.class, () -> householdService.regenerateInviteCode(guest));
        assertEquals("OLD999", home.getInviteCode());
    }

    @Test
    void regenerateInviteCode_throwsWhenHouseholdMissing() {
        var home = household("ABC123");
        var user = hostOf(home, "Ana");
        when(householdRepository.findById(home.getId())).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> householdService.regenerateInviteCode(user));
    }

    // ---------- removeMember ----------

    @Test
    void removeMember_hostKicks_targetReturnsToOriginWithRestoreAndCopy_remainingNotified() {
        var home = household("FAM111");
        var host = hostOf(home, "Ana");
        var targetOrigin = household("TGT001");
        var joinedAt = LocalDateTime.now().minusDays(4);
        var target = user("Target", home, targetOrigin, joinedAt);
        var bystander = user("Bystander", home, household("BYS001"), LocalDateTime.now().minusDays(1));
        when(householdRepository.findById(home.getId())).thenReturn(Optional.of(home));
        when(householdRepository.findById(targetOrigin.getId())).thenReturn(Optional.of(targetOrigin));
        members(home, host, target, bystander);
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));

        var response = householdService.removeMember(host, target.getId());

        assertEquals(targetOrigin.getId(), target.getHousehold().getId());
        assertEquals(target, targetOrigin.getOwner(), "kicked member hosts their own household again");
        assertEquals(home.getId(), response.id());
        assertEquals(2, response.members().size());
        verify(mergeService).restoreOriginals(targetOrigin);
        verify(mergeService).copyGeneratedSince(home, targetOrigin, joinedAt);
        verify(userRepository).save(target);
        verify(householdCacheGen).bump(home.getId());
        assertEquals(host, home.getOwner(), "host unchanged");
        // bystander is told; the actor already knows
        var captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService).notifyAfterCommit(captor.capture());
        assertEquals(bystander, captor.getValue().user());
        assertEquals(NotificationType.HOUSEHOLD_MEMBER_LEFT, captor.getValue().type());
    }

    @Test
    void removeMember_nonHost_isForbidden() {
        var home = household("FAM111");
        var host = hostOf(home, "Ana");
        var actor = user("Member", home, household("MEM001"), LocalDateTime.now());
        var target = user("Target", home, household("TGT001"), LocalDateTime.now());
        when(householdRepository.findById(home.getId())).thenReturn(Optional.of(home));
        members(home, host, actor, target);
        var targetId = target.getId();

        assertThrows(NotHouseholdHostException.class, () -> householdService.removeMember(actor, targetId));
        verify(userRepository, never()).save(any());
        verify(mergeService, never()).restoreOriginals(any());
        verify(mergeService, never()).copyGeneratedSince(any(), any(), any());
    }

    @Test
    void removeMember_legacyHouseholdWithoutHost_longestStandingMemberIsHost() {
        var home = household("FAM111");
        var elder = user("Elder", home, home, LONG_AGO);
        var newer = user("Newer", home, household("NEW001"), LocalDateTime.now());
        when(householdRepository.findById(home.getId())).thenReturn(Optional.of(home));
        members(home, newer, elder);
        var elderId = elder.getId();

        assertThrows(NotHouseholdHostException.class, () -> householdService.removeMember(newer, elderId));
        assertEquals(elder, home.getOwner());
    }

    @Test
    void removeMember_throwsWhenActorTriesToKickThemselves() {
        var home = household("FAM111");
        var host = hostOf(home, "Ana");
        when(householdRepository.findById(home.getId())).thenReturn(Optional.of(home));
        members(home, host);
        var actorId = host.getId();

        assertThrows(IllegalArgumentException.class, () -> householdService.removeMember(host, actorId));
        verify(userRepository, never()).save(any());
    }

    @Test
    void removeMember_throwsWhenActorHouseholdMissing() {
        var home = household("FAM111");
        var host = hostOf(home, "Ana");
        when(householdRepository.findById(home.getId())).thenReturn(Optional.empty());
        var memberId = UUID.randomUUID();

        assertThrows(IllegalStateException.class, () -> householdService.removeMember(host, memberId));
    }

    @Test
    void removeMember_throwsWhenTargetNotFound() {
        var home = household("FAM111");
        var host = hostOf(home, "Ana");
        var memberId = UUID.randomUUID();
        when(householdRepository.findById(home.getId())).thenReturn(Optional.of(home));
        members(home, host);
        when(userRepository.findById(memberId)).thenReturn(Optional.empty());

        assertThrows(NotInHouseholdException.class, () -> householdService.removeMember(host, memberId));
    }

    @Test
    void removeMember_throwsWhenTargetInDifferentHousehold() {
        var home = household("FAM111");
        var host = hostOf(home, "Ana");
        var other = household("OTHER2");
        var target = hostOf(other, "Bruno");
        when(householdRepository.findById(home.getId())).thenReturn(Optional.of(home));
        members(home, host);
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        var targetId = target.getId();

        assertThrows(NotInHouseholdException.class, () -> householdService.removeMember(host, targetId));
        verify(userRepository, never()).save(any());
    }

    // ---------- join: expired code ----------

    @Test
    void join_throwsWhenInviteCodeExpired() {
        var home = household("CUR123");
        var user = hostOf(home, "Ana");
        var target = household("EXP456");
        target.setInviteCodeExpiresAt(LocalDateTime.now().minusHours(1));
        when(householdRepository.findByInviteCode("EXP456")).thenReturn(Optional.of(target));
        var request = new JoinHouseholdRequest("exp456", null, null);

        assertThrows(InvalidInviteCodeException.class, () -> householdService.join(user, request));
        verify(userRepository, never()).save(any());
        verify(mergeService, never()).merge(eq(home), any(), any());
    }
}
