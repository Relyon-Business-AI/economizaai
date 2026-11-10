package com.relyon.economizaai.service;

import com.relyon.economizaai.dto.request.JoinHouseholdRequest;
import com.relyon.economizaai.exception.AlreadyInHouseholdException;
import com.relyon.economizaai.exception.HouseholdFullException;
import com.relyon.economizaai.exception.InvalidInviteCodeException;
import com.relyon.economizaai.exception.SoloHouseholdLeaveException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.MergeCategory;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HouseholdServiceTest {

    @Mock
    private HouseholdRepository householdRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ReceiptRepository receiptRepository;

    @Mock
    private HouseholdMergeService mergeService;

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
        // Unit tests run without Spring, so @Value never fires: the production default is ON.
        ReflectionTestUtils.setField(householdService, "mergeEnabled", true);
        // Room for everyone unless a test says otherwise; translations echo their key.
        lenient().when(subscriptionGate.householdMemberCap(anyList()))
                .thenReturn(new SubscriptionGateService.MemberCap(6, false));
        lenient().when(messageService.translate(anyString(), any(java.util.Locale.class), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(householdRepository.save(any(Household.class))).thenAnswer(invocation -> {
            var saved = invocation.<Household>getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
                saved.setCreatedAt(LocalDateTime.now());
            }
            return saved;
        });
        lenient().when(mergeService.merge(any(), any(), any()))
                .thenReturn(new HouseholdMergeService.MergeResult(0, 0));
        lenient().when(mergeService.restoreOriginals(any()))
                .thenReturn(new HouseholdMergeService.MergeResult(0, 0));
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

    private Household buildHousehold(String code) {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode(code).build();
        household.setCreatedAt(LocalDateTime.now());
        household.setUpdatedAt(LocalDateTime.now());
        return household;
    }

    private void previousHouseholdIsDeletable(Household previous) {
        when(userRepository.countByHouseholdId(previous.getId())).thenReturn(0L);
        when(receiptRepository.existsByHouseholdId(previous.getId())).thenReturn(false);
        when(receiptRepository.existsByOriginHouseholdId(previous.getId())).thenReturn(false);
    }

    // ---------- create / getMine ----------

    @Test
    void createSoloHousehold_persistsWithGeneratedInviteCode() {
        when(householdRepository.existsByInviteCode(any())).thenReturn(false);

        var saved = householdService.createSoloHousehold();

        assertNotNull(saved.getInviteCode());
        assertEquals(6, saved.getInviteCode().length());
        assertNotNull(saved.getInviteCodeExpiresAt());
        assertNull(saved.getOwner(), "host is assigned once the user row exists");
    }

    @Test
    void assignHost_setsOwnerAndSaves() {
        var household = buildHousehold("ABC123");
        var user = buildUser(household);

        householdService.assignHost(household, user);

        assertEquals(user, household.getOwner());
        verify(householdRepository).save(household);
    }

    @Test
    void getMine_returnsHouseholdWithMembersAndHost() {
        var household = buildHousehold("ABC123");
        var user = buildUser(household);
        household.setOwner(user);
        when(householdRepository.findById(household.getId())).thenReturn(Optional.of(household));
        when(userRepository.findAllByHouseholdId(household.getId())).thenReturn(List.of(user));

        var response = householdService.getMine(user);

        assertEquals(household.getId(), response.id());
        assertEquals("ABC123", response.inviteCode());
        assertEquals(user.getId(), response.hostId());
        assertEquals(1, response.members().size());
        assertEquals("john@test.com", response.members().get(0).email());
    }

    @Test
    void getMine_selfHealsMissingHostToMostSeniorMember() {
        var household = buildHousehold("ABC123");
        var newer = buildUser(household, "Newer", LocalDateTime.now());
        var elder = buildUser(household, "Elder", LocalDateTime.now().minusDays(30));
        when(householdRepository.findById(household.getId())).thenReturn(Optional.of(household));
        when(userRepository.findAllByHouseholdId(household.getId())).thenReturn(List.of(newer, elder));

        var response = householdService.getMine(newer);

        assertEquals(elder.getId(), response.hostId());
        assertEquals(elder, household.getOwner());
        verify(householdRepository).save(household);
    }

    // ---------- rename ----------

    @Test
    void rename_trimsAndSaves_blankClears() {
        var household = buildHousehold("ABC123");
        var user = buildUser(household);
        when(householdRepository.findById(household.getId())).thenReturn(Optional.of(household));
        when(userRepository.findAllByHouseholdId(household.getId())).thenReturn(List.of(user));

        assertEquals("Casa da Ana", householdService.rename(user, "  Casa da Ana ").name());
        assertNull(householdService.rename(user, "   ").name());
        assertNull(householdService.rename(user, null).name());
    }

    // ---------- previewInvite ----------

    @Test
    void previewInvite_returnsNameHostAndFirstNamesOnly() {
        var target = buildHousehold("NEW456");
        target.setName("Casa Teste");
        var host = buildUser(target, "Ana Maria", LocalDateTime.now().minusDays(10));
        var other = buildUser(target, "Bruno Silva", LocalDateTime.now());
        target.setOwner(host);
        when(householdRepository.findByInviteCode("NEW456")).thenReturn(Optional.of(target));
        when(userRepository.findAllByHouseholdId(target.getId())).thenReturn(List.of(other, host));

        var preview = householdService.previewInvite("new456");

        assertEquals("Casa Teste", preview.householdName());
        assertEquals("Ana", preview.hostFirstName());
        assertEquals(2, preview.memberCount());
        assertEquals(List.of("Ana", "Bruno"), preview.memberFirstNames());
    }

    @Test
    void previewInvite_throwsForExpiredCode() {
        var target = buildHousehold("EXP456");
        target.setInviteCodeExpiresAt(LocalDateTime.now().minusHours(1));
        when(householdRepository.findByInviteCode("EXP456")).thenReturn(Optional.of(target));

        assertThrows(InvalidInviteCodeException.class, () -> householdService.previewInvite("EXP456"));
    }

    // ---------- join ----------

    @Test
    void join_movesUserMergesAllDataByDefaultAndDeletesEmptyPrevious() {
        var previous = buildHousehold("OLD123");
        var target = buildHousehold("NEW456");
        var user = buildUser(previous);
        var host = buildUser(target, "Host", LocalDateTime.now().minusDays(5));
        target.setOwner(host);

        when(householdRepository.findByInviteCode("NEW456")).thenReturn(Optional.of(target));
        when(userRepository.findAllByHouseholdId(target.getId()))
                .thenReturn(List.of(host))            // before: capacity + recipients
                .thenReturn(List.of(host, user));     // after: response
        previousHouseholdIsDeletable(previous);

        var response = householdService.join(user, new JoinHouseholdRequest("new456", null, null));

        assertEquals(target.getId(), user.getHousehold().getId());
        assertEquals(target.getId(), response.id());
        assertEquals(2, response.members().size());
        verify(mergeService).merge(previous, target, Set.of(MergeCategory.values()));
        verify(householdCacheGen).bump(previous.getId());
        verify(householdCacheGen).bump(target.getId());
        verify(householdRepository).delete(previous);
    }

    @Test
    void join_notifiesExistingMembersButNotTheJoiner() {
        var previous = buildHousehold("OLD123");
        var target = buildHousehold("NEW456");
        var user = buildUser(previous, "Carla", LocalDateTime.now());
        var host = buildUser(target, "Host", LocalDateTime.now().minusDays(5));
        when(householdRepository.findByInviteCode("NEW456")).thenReturn(Optional.of(target));
        when(userRepository.findAllByHouseholdId(target.getId())).thenReturn(List.of(host));
        when(userRepository.countByHouseholdId(previous.getId())).thenReturn(0L);
        when(receiptRepository.existsByHouseholdId(previous.getId())).thenReturn(true);

        householdService.join(user, new JoinHouseholdRequest("NEW456", null, null));

        var captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService).notifyAfterCommit(captor.capture());
        var payload = captor.getValue();
        assertEquals(host, payload.user());
        assertEquals(NotificationType.HOUSEHOLD_MEMBER_JOINED, payload.type());
        assertEquals("household.member.joined.title", payload.title());
    }

    @Test
    void join_skipsMergeWhenCallerOptsOut() {
        var previous = buildHousehold("OLD123");
        var target = buildHousehold("NEW456");
        var user = buildUser(previous);
        when(householdRepository.findByInviteCode("NEW456")).thenReturn(Optional.of(target));
        when(userRepository.findAllByHouseholdId(target.getId())).thenReturn(List.of());
        when(userRepository.countByHouseholdId(previous.getId())).thenReturn(0L);
        when(receiptRepository.existsByHouseholdId(previous.getId())).thenReturn(true);

        householdService.join(user, new JoinHouseholdRequest("NEW456", false, null));

        verify(mergeService, never()).merge(any(), any(), any());
    }

    @Test
    void join_skipsMergeWhenFeatureDisabled() {
        ReflectionTestUtils.setField(householdService, "mergeEnabled", false);
        var previous = buildHousehold("OLD123");
        var target = buildHousehold("NEW456");
        var user = buildUser(previous);
        when(householdRepository.findByInviteCode("NEW456")).thenReturn(Optional.of(target));
        when(userRepository.findAllByHouseholdId(target.getId())).thenReturn(List.of());
        when(userRepository.countByHouseholdId(previous.getId())).thenReturn(0L);
        when(receiptRepository.existsByHouseholdId(previous.getId())).thenReturn(true);

        householdService.join(user, new JoinHouseholdRequest("NEW456", true, null));

        verify(mergeService, never()).merge(any(), any(), any());
    }

    @Test
    void join_keepsEmptyPreviousHouseholdWhenItStillOwnsData() {
        var previous = buildHousehold("OLD123");
        var target = buildHousehold("NEW456");
        var user = buildUser(previous);
        when(householdRepository.findByInviteCode("NEW456")).thenReturn(Optional.of(target));
        when(userRepository.countByHouseholdId(previous.getId())).thenReturn(0L);
        // no members, but data (receipts) still reference it as origin -> must NOT delete
        when(receiptRepository.existsByHouseholdId(previous.getId())).thenReturn(false);
        when(receiptRepository.existsByOriginHouseholdId(previous.getId())).thenReturn(true);
        when(userRepository.findAllByHouseholdId(target.getId())).thenReturn(List.of(user));

        householdService.join(user, new JoinHouseholdRequest("NEW456", null, null));

        verify(householdRepository, never()).delete(previous);
    }

    @Test
    void join_refusesWhenHouseholdIsFull() {
        var previous = buildHousehold("OLD123");
        var target = buildHousehold("NEW456");
        var user = buildUser(previous);
        var memberA = buildUser(target, "A", LocalDateTime.now());
        var memberB = buildUser(target, "B", LocalDateTime.now());
        when(householdRepository.findByInviteCode("NEW456")).thenReturn(Optional.of(target));
        when(userRepository.findAllByHouseholdId(target.getId())).thenReturn(List.of(memberA, memberB));
        when(subscriptionGate.householdMemberCap(anyList()))
                .thenReturn(new SubscriptionGateService.MemberCap(2, true));
        var request = new JoinHouseholdRequest("NEW456", null, null);

        var thrown = assertThrows(HouseholdFullException.class, () -> householdService.join(user, request));

        assertTrue(thrown.isUpgradeAvailable());
        assertEquals(previous.getId(), user.getHousehold().getId());
        verify(userRepository, never()).save(any());
        verify(mergeService, never()).merge(any(), any(), any());
    }

    @Test
    void join_capCountsTheJoinerWhenAskingTheGate() {
        var previous = buildHousehold("OLD123");
        var target = buildHousehold("NEW456");
        var user = buildUser(previous);
        var host = buildUser(target, "Host", LocalDateTime.now());
        when(householdRepository.findByInviteCode("NEW456")).thenReturn(Optional.of(target));
        when(userRepository.findAllByHouseholdId(target.getId())).thenReturn(List.of(host));
        when(userRepository.countByHouseholdId(previous.getId())).thenReturn(0L);
        when(receiptRepository.existsByHouseholdId(previous.getId())).thenReturn(true);

        householdService.join(user, new JoinHouseholdRequest("NEW456", null, null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<User>> everyone = ArgumentCaptor.forClass(List.class);
        verify(subscriptionGate).householdMemberCap(everyone.capture());
        assertTrue(everyone.getValue().contains(user));
        assertTrue(everyone.getValue().contains(host));
    }

    @Test
    void join_throwsForInvalidCode() {
        var household = buildHousehold("ABC123");
        var user = buildUser(household);
        when(householdRepository.findByInviteCode("XYZ999")).thenReturn(Optional.empty());
        var request = new JoinHouseholdRequest("XYZ999", null, null);

        assertThrows(InvalidInviteCodeException.class, () -> householdService.join(user, request));
    }

    @Test
    void join_throwsWhenAlreadyInTargetHousehold() {
        var household = buildHousehold("ABC123");
        var user = buildUser(household);
        when(householdRepository.findByInviteCode("ABC123")).thenReturn(Optional.of(household));
        var request = new JoinHouseholdRequest("ABC123", null, null);

        assertThrows(AlreadyInHouseholdException.class, () -> householdService.join(user, request));
    }

    // ---------- leave ----------

    @Test
    void leave_refusedWhenAloneInHousehold() {
        var household = buildHousehold("SOLO01");
        var user = buildUser(household);
        when(userRepository.findAllByHouseholdId(household.getId())).thenReturn(List.of(user));

        assertThrows(SoloHouseholdLeaveException.class, () -> householdService.leave(user));

        assertEquals(household.getId(), user.getHousehold().getId());
        verify(userRepository, never()).save(any());
    }

    @Test
    void leave_reclaimsOriginHomeRestoresDataAndNotifiesRemaining() {
        var shared = buildHousehold("FAM111");
        var origin = buildHousehold("OLD123");
        var leaver = buildUser(shared, "Leaver", LocalDateTime.now());
        var stayer = buildUser(shared, "Stayer", LocalDateTime.now().minusDays(3));
        shared.setOwner(stayer);
        var broughtReceipt = Receipt.builder().originHousehold(origin).build();
        when(userRepository.findAllByHouseholdId(shared.getId())).thenReturn(List.of(leaver, stayer));
        when(receiptRepository.findFirstByUserIdAndOriginHouseholdIdNotOrderByCreatedAtAsc(leaver.getId(), shared.getId()))
                .thenReturn(Optional.of(broughtReceipt));
        when(householdRepository.findById(origin.getId())).thenReturn(Optional.of(origin));
        when(userRepository.countByHouseholdId(shared.getId())).thenReturn(1L);

        var response = householdService.leave(leaver);

        assertEquals(origin.getId(), leaver.getHousehold().getId());
        assertEquals(origin.getId(), response.id());
        assertEquals(leaver.getId(), response.hostId(), "leaver hosts the home they return to");
        verify(mergeService).restoreOriginals(origin);
        verify(householdCacheGen).bump(shared.getId());
        verify(householdCacheGen).bump(origin.getId());
        var captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService).notifyAfterCommit(captor.capture());
        assertEquals(stayer, captor.getValue().user());
        assertEquals(NotificationType.HOUSEHOLD_MEMBER_LEFT, captor.getValue().type());
    }

    @Test
    void leave_hostLeaving_transfersHostToMostSeniorRemainingMember() {
        var shared = buildHousehold("FAM111");
        var host = buildUser(shared, "Host", LocalDateTime.now().minusDays(30));
        var elder = buildUser(shared, "Elder", LocalDateTime.now().minusDays(20));
        var newer = buildUser(shared, "Newer", LocalDateTime.now());
        shared.setOwner(host);
        when(userRepository.findAllByHouseholdId(shared.getId())).thenReturn(List.of(host, newer, elder));
        when(householdRepository.existsByInviteCode(any())).thenReturn(false);
        when(receiptRepository.findFirstByUserIdAndOriginHouseholdIdNotOrderByCreatedAtAsc(any(), any()))
                .thenReturn(Optional.empty());
        when(userRepository.countByHouseholdId(shared.getId())).thenReturn(2L);

        householdService.leave(host);

        assertEquals(elder, shared.getOwner());
        assertNotEquals(shared.getId(), host.getHousehold().getId());
    }

    @Test
    void leave_withoutOriginGetsFreshSoloHouseholdAndDeletesEmptyPrevious() {
        var previous = buildHousehold("OLD123");
        var user = buildUser(previous);
        var other = buildUser(previous, "Other", LocalDateTime.now());
        when(userRepository.findAllByHouseholdId(previous.getId())).thenReturn(List.of(user, other));
        when(householdRepository.existsByInviteCode(any())).thenReturn(false);
        when(receiptRepository.findFirstByUserIdAndOriginHouseholdIdNotOrderByCreatedAtAsc(any(), any()))
                .thenReturn(Optional.empty());
        previousHouseholdIsDeletable(previous);

        var response = householdService.leave(user);

        assertNotEquals(previous.getId(), user.getHousehold().getId());
        assertNotNull(response.inviteCode());
        assertEquals(user.getId(), response.hostId());
        verify(householdRepository).delete(previous);
    }

    @Test
    void leave_mergeDisabled_stillGetsFreshSoloHousehold() {
        ReflectionTestUtils.setField(householdService, "mergeEnabled", false);
        var previous = buildHousehold("OLD123");
        var user = buildUser(previous);
        var other = buildUser(previous, "Other", LocalDateTime.now());
        when(userRepository.findAllByHouseholdId(previous.getId())).thenReturn(List.of(user, other));
        when(householdRepository.existsByInviteCode(any())).thenReturn(false);
        when(userRepository.countByHouseholdId(previous.getId())).thenReturn(1L);

        householdService.leave(user);

        assertNotEquals(previous.getId(), user.getHousehold().getId());
        verify(mergeService, never()).restoreOriginals(any());
        assertFalse(user.getHousehold().getInviteCode().isBlank());
    }
}
