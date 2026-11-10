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
import org.springframework.test.util.ReflectionTestUtils;

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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the HouseholdService branches not exercised by {@link HouseholdServiceTest}:
 * getMine/regenerate not-found, removeMember (all paths, host-only rule, restore on kick),
 * expired invite code, and the regenerate happy path.
 */
@ExtendWith(MockitoExtension.class)
class HouseholdServiceCoverageTest {

    @Mock
    private HouseholdRepository householdRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ReceiptRepository receiptRepository;

    @Mock
    private HouseholdMergeService mergeService;

    @Mock
    private HouseholdSplitService splitService;

    @Mock
    private HouseholdCacheGen householdCacheGen;

    @Mock
    private SubscriptionGateService subscriptionGate;

    @Mock
    private NotificationService notificationService;

    @Mock
    private LocalizedMessageService messageService;

    @InjectMocks
    private HouseholdService householdService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(householdService, "mergeEnabled", true);
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
        lenient().when(mergeService.restoreOriginals(any()))
                .thenReturn(new HouseholdMergeService.MergeResult(0, 0));
        lenient().when(splitService.copyInto(any(), any(), any()))
                .thenReturn(new HouseholdSplitService.CopyResult(0, 0, 0));
    }

    private User buildUser(Household household, String name, LocalDateTime createdAt) {
        var user = User.builder()
                .id(UUID.randomUUID())
                .name(name)
                .email(name.toLowerCase() + "@test.com")
                .password("encoded")
                .household(household)
                .build();
        user.setCreatedAt(createdAt);
        user.setUpdatedAt(createdAt);
        return user;
    }

    private User buildUser(Household household) {
        return buildUser(household, "John", LocalDateTime.now());
    }

    private Household buildHousehold(String inviteCode) {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode(inviteCode).build();
        household.setCreatedAt(LocalDateTime.now());
        household.setUpdatedAt(LocalDateTime.now());
        lenient().when(householdRepository.findById(household.getId())).thenReturn(Optional.of(household));
        return household;
    }

    // ---------- getMine ----------

    @Test
    void getMine_throwsWhenHouseholdMissing() {
        var household = buildHousehold("ABC123");
        var user = buildUser(household);
        when(householdRepository.findById(household.getId())).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> householdService.getMine(user));
    }

    // ---------- regenerateInviteCode ----------

    @Test
    void regenerateInviteCode_rotatesCodeAndReturnsMembers() {
        var household = buildHousehold("OLD999");
        var user = buildUser(household);
        household.setOwner(user);
        when(householdRepository.findById(household.getId())).thenReturn(Optional.of(household));
        when(householdRepository.existsByInviteCode(any())).thenReturn(false);
        when(userRepository.findAllByHouseholdId(household.getId())).thenReturn(List.of(user));

        var response = householdService.regenerateInviteCode(user);

        assertNotNull(response.inviteCode());
        assertEquals(6, response.inviteCode().length());
        assertNotEquals("OLD999", response.inviteCode());
        assertNotNull(household.getInviteCodeExpiresAt());
        verify(householdRepository).save(household);
    }

    @Test
    void regenerateInviteCode_throwsWhenHouseholdMissing() {
        var household = buildHousehold("ABC123");
        var user = buildUser(household);
        when(householdRepository.findById(household.getId())).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> householdService.regenerateInviteCode(user));
    }

    // ---------- removeMember ----------

    @Test
    void removeMember_hostKicks_targetReturnsHomeWithRestoreAndCopySinceJoin() {
        var household = buildHousehold("FAM111");
        var origin = buildHousehold("ORG222");
        var actor = buildUser(household, "Host", LocalDateTime.now().minusDays(10));
        var target = buildUser(household, "Target", LocalDateTime.now());
        target.setHomeHousehold(origin);
        var joinedAt = LocalDateTime.now().minusDays(4);
        target.setHouseholdJoinedAt(joinedAt);
        var bystander = buildUser(household, "Bystander", LocalDateTime.now().minusDays(1));
        household.setOwner(actor);
        when(householdRepository.findById(household.getId())).thenReturn(Optional.of(household));
        when(userRepository.findAllByHouseholdId(household.getId())).thenReturn(List.of(actor, target, bystander));
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));

        var response = householdService.removeMember(actor, target.getId());

        assertEquals(origin.getId(), target.getHousehold().getId());
        assertEquals(target, origin.getOwner(), "kicked member hosts the home they return to");
        assertEquals(household.getId(), response.id());
        assertEquals(2, response.members().size());
        verify(mergeService).restoreOriginals(origin);
        verify(splitService).copyInto(household, origin, joinedAt);
        verify(userRepository).save(target);
        verify(householdCacheGen).bump(household.getId());
        // bystander is told; the actor already knows
        var captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService).notifyAfterCommit(captor.capture());
        assertEquals(bystander, captor.getValue().user());
        assertEquals(NotificationType.HOUSEHOLD_MEMBER_LEFT, captor.getValue().type());
    }

    @Test
    void removeMember_nonHost_isForbidden() {
        var household = buildHousehold("FAM111");
        var host = buildUser(household, "Host", LocalDateTime.now().minusDays(10));
        var actor = buildUser(household, "Member", LocalDateTime.now());
        var target = buildUser(household, "Target", LocalDateTime.now());
        household.setOwner(host);
        when(householdRepository.findById(household.getId())).thenReturn(Optional.of(household));
        when(userRepository.findAllByHouseholdId(household.getId())).thenReturn(List.of(host, actor, target));
        var targetId = target.getId();

        assertThrows(NotHouseholdHostException.class, () -> householdService.removeMember(actor, targetId));
        verify(userRepository, never()).save(any());
        verify(mergeService, never()).restoreOriginals(any());
    }

    @Test
    void removeMember_legacyHouseholdWithoutHost_mostSeniorMemberIsHost() {
        var household = buildHousehold("FAM111");
        var elder = buildUser(household, "Elder", LocalDateTime.now().minusDays(10));
        var newer = buildUser(household, "Newer", LocalDateTime.now());
        when(householdRepository.findById(household.getId())).thenReturn(Optional.of(household));
        when(userRepository.findAllByHouseholdId(household.getId())).thenReturn(List.of(newer, elder));
        var elderId = elder.getId();

        assertThrows(NotHouseholdHostException.class, () -> householdService.removeMember(newer, elderId));
        assertEquals(elder, household.getOwner());
    }

    @Test
    void removeMember_throwsWhenActorTriesToKickThemselves() {
        var household = buildHousehold("FAM111");
        var actor = buildUser(household);
        household.setOwner(actor);
        when(householdRepository.findById(household.getId())).thenReturn(Optional.of(household));
        when(userRepository.findAllByHouseholdId(household.getId())).thenReturn(List.of(actor));
        var actorId = actor.getId();

        assertThrows(IllegalArgumentException.class, () -> householdService.removeMember(actor, actorId));
        verify(userRepository, never()).save(any());
    }

    @Test
    void removeMember_throwsWhenActorHouseholdMissing() {
        var household = buildHousehold("FAM111");
        var actor = buildUser(household);
        when(householdRepository.findById(household.getId())).thenReturn(Optional.empty());
        var memberId = UUID.randomUUID();

        assertThrows(IllegalStateException.class,
                () -> householdService.removeMember(actor, memberId));
    }

    @Test
    void removeMember_throwsWhenTargetNotFound() {
        var household = buildHousehold("FAM111");
        var actor = buildUser(household);
        household.setOwner(actor);
        var memberId = UUID.randomUUID();
        when(householdRepository.findById(household.getId())).thenReturn(Optional.of(household));
        when(userRepository.findAllByHouseholdId(household.getId())).thenReturn(List.of(actor));
        when(userRepository.findById(memberId)).thenReturn(Optional.empty());

        assertThrows(NotInHouseholdException.class, () -> householdService.removeMember(actor, memberId));
    }

    @Test
    void removeMember_throwsWhenTargetInDifferentHousehold() {
        var actorHousehold = buildHousehold("FAM111");
        var otherHousehold = buildHousehold("OTHER2");
        var actor = buildUser(actorHousehold);
        var target = buildUser(otherHousehold);
        actorHousehold.setOwner(actor);
        when(householdRepository.findById(actorHousehold.getId())).thenReturn(Optional.of(actorHousehold));
        when(userRepository.findAllByHouseholdId(actorHousehold.getId())).thenReturn(List.of(actor));
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        var targetId = target.getId();

        assertThrows(NotInHouseholdException.class, () -> householdService.removeMember(actor, targetId));
        verify(userRepository, never()).save(any());
    }

    // ---------- join: expired code ----------

    @Test
    void join_throwsWhenInviteCodeExpired() {
        var current = buildHousehold("CUR123");
        var target = buildHousehold("EXP456");
        target.setInviteCodeExpiresAt(LocalDateTime.now().minusHours(1));
        var user = buildUser(current);
        when(householdRepository.findByInviteCode("EXP456")).thenReturn(Optional.of(target));
        var request = new JoinHouseholdRequest("exp456", null, null);

        assertThrows(InvalidInviteCodeException.class,
                () -> householdService.join(user, request));
        verify(userRepository, never()).save(any());
    }
}
