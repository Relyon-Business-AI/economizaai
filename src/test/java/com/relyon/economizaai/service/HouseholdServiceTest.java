package com.relyon.economizaai.service;

import com.relyon.economizaai.dto.request.JoinHouseholdRequest;
import com.relyon.economizaai.exception.AlreadyInHouseholdException;
import com.relyon.economizaai.exception.HouseholdFullException;
import com.relyon.economizaai.exception.InvalidInviteCodeException;
import com.relyon.economizaai.exception.SoloHouseholdLeaveException;
import com.relyon.economizaai.model.Household;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The membership rules: host/guest, origin recorded on the user, bring-or-keep data on
 * join, restore + copy on departure, host inheritance, last-member evacuation.
 */
@ExtendWith(MockitoExtension.class)
class HouseholdServiceTest {

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
        lenient().when(subscriptionGate.householdMemberCap(anyList()))
                .thenReturn(new SubscriptionGateService.MemberCap(6, false));
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
        lenient().when(mergeService.merge(any(), any(), any())).thenReturn(new HouseholdMergeService.MergeResult(0, 0));
        lenient().when(mergeService.restoreOriginals(any())).thenReturn(new HouseholdMergeService.MergeResult(0, 0));
        lenient().when(mergeService.evacuate(any(), any())).thenReturn(new HouseholdMergeService.MergeResult(0, 0));
        lenient().when(mergeService.copyGeneratedSince(any(), any(), any())).thenReturn(0);
    }

    // ---------- fixtures ----------

    private Household household(String code) {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode(code).build();
        household.setCreatedAt(LocalDateTime.now());
        household.setUpdatedAt(LocalDateTime.now());
        return household;
    }

    /** A user living in {@code current}, whose own household is {@code origin}, since {@code joinedAt}. */
    private User user(String name, Household current, Household origin, LocalDateTime joinedAt) {
        var user = User.builder()
                .id(UUID.randomUUID())
                .name(name)
                .email(name.toLowerCase().replace(' ', '.') + "@test.com")
                .password("encoded")
                .household(current)
                .originHousehold(origin)
                .householdJoinedAt(joinedAt)
                .build();
        user.setCreatedAt(joinedAt);
        user.setUpdatedAt(joinedAt);
        return user;
    }

    /** A host at home: their current household is their origin, and they own it. */
    private User hostOf(Household home, String name) {
        var host = user(name, home, home, LONG_AGO);
        home.setOwner(host);
        return host;
    }

    private void members(Household household, User... members) {
        when(userRepository.findAllByHouseholdId(household.getId())).thenReturn(List.of(members));
    }

    private void originStillExists(Household origin) {
        when(householdRepository.findById(origin.getId())).thenReturn(Optional.of(origin));
    }

    // ---------- create / host assignment ----------

    @Test
    void createSoloHousehold_persistsWithGeneratedInviteCode() {
        var saved = householdService.createSoloHousehold();

        assertNotNull(saved.getInviteCode());
        assertEquals(6, saved.getInviteCode().length());
        assertNotNull(saved.getInviteCodeExpiresAt());
        assertNull(saved.getOwner(), "host is assigned once the user row exists");
    }

    @Test
    void assignHost_recordsOwnerOriginAndJoinInstant() {
        var home = household("ABC123");
        var user = user("Ana", home, null, null);

        householdService.assignHost(home, user);

        assertEquals(user, home.getOwner());
        assertEquals(home, user.getOriginHousehold());
        assertNotNull(user.getHouseholdJoinedAt());
        verify(householdRepository).save(home);
        verify(userRepository).save(user);
    }

    // ---------- getMine / rename / preview ----------

    @Test
    void getMine_returnsHouseholdWithMembersAndHost() {
        var home = household("ABC123");
        var host = hostOf(home, "Ana");
        when(householdRepository.findById(home.getId())).thenReturn(Optional.of(home));
        members(home, host);

        var response = householdService.getMine(host);

        assertEquals(home.getId(), response.id());
        assertEquals("ABC123", response.inviteCode());
        assertEquals(host.getId(), response.hostId());
        assertEquals(1, response.members().size());
    }

    @Test
    void getMine_selfHealsMissingHostToLongestStandingMember() {
        var home = household("ABC123");
        var elder = user("Elder", home, null, LONG_AGO);
        var newer = user("Newer", home, null, LocalDateTime.now());
        when(householdRepository.findById(home.getId())).thenReturn(Optional.of(home));
        members(home, newer, elder);

        var response = householdService.getMine(newer);

        assertEquals(elder.getId(), response.hostId());
        assertEquals(elder, home.getOwner());
    }

    @Test
    void rename_trimsAndSaves_blankClears() {
        var home = household("ABC123");
        var host = hostOf(home, "Ana");
        when(householdRepository.findById(home.getId())).thenReturn(Optional.of(home));
        members(home, host);

        assertEquals("Casa da Ana", householdService.rename(host, "  Casa da Ana ").name());
        assertNull(householdService.rename(host, "   ").name());
        assertNull(householdService.rename(host, null).name());
    }

    @Test
    void previewInvite_returnsNameHostAndFirstNamesOnly() {
        var home = household("NEW456");
        home.setName("Casa Teste");
        var host = hostOf(home, "Ana Maria");
        var guest = user("Bruno Silva", home, household("GST001"), LocalDateTime.now());
        when(householdRepository.findByInviteCode("NEW456")).thenReturn(Optional.of(home));
        members(home, guest, host);

        var preview = householdService.previewInvite("new456");

        assertEquals("Casa Teste", preview.householdName());
        assertEquals("Ana", preview.hostFirstName());
        assertEquals(2, preview.memberCount());
        assertEquals(List.of("Ana", "Bruno"), preview.memberFirstNames());
    }

    @Test
    void previewInvite_throwsForExpiredCode() {
        var target = household("EXP456");
        target.setInviteCodeExpiresAt(LocalDateTime.now().minusHours(1));
        when(householdRepository.findByInviteCode("EXP456")).thenReturn(Optional.of(target));

        assertThrows(InvalidInviteCodeException.class, () -> householdService.previewInvite("EXP456"));
    }

    // ---------- join ----------

    @Test
    void join_soloHost_movesMembershipMergesDataAndKeepsOriginAlive() {
        var ownHome = household("OLD123");
        var joiner = hostOf(ownHome, "Bruno");
        var target = household("NEW456");
        var host = hostOf(target, "Ana");
        when(householdRepository.findByInviteCode("NEW456")).thenReturn(Optional.of(target));
        members(ownHome, joiner);
        when(userRepository.findAllByHouseholdId(target.getId()))
                .thenReturn(List.of(host))             // capacity + recipients
                .thenReturn(List.of(host, joiner));    // response

        var response = householdService.join(joiner, new JoinHouseholdRequest("new456", null, null));

        assertEquals(target.getId(), joiner.getHousehold().getId());
        assertEquals(ownHome, joiner.getOriginHousehold(), "origin never changes by joining");
        assertNotEquals(LONG_AGO, joiner.getHouseholdJoinedAt(), "join instant is refreshed");
        assertEquals(2, response.members().size());
        assertEquals(host.getId(), response.hostId());
        verify(mergeService).merge(ownHome, target, Set.of(MergeCategory.values()));
        verify(householdRepository, never()).delete(ownHome);
        verify(householdCacheGen).bump(ownHome.getId());
        verify(householdCacheGen).bump(target.getId());
    }

    @Test
    void join_withBringDataFalse_leavesDataParkedAtOrigin() {
        var ownHome = household("OLD123");
        var joiner = hostOf(ownHome, "Bruno");
        var target = household("NEW456");
        hostOf(target, "Ana");
        when(householdRepository.findByInviteCode("NEW456")).thenReturn(Optional.of(target));
        members(ownHome, joiner);
        members(target, target.getOwner());

        householdService.join(joiner, new JoinHouseholdRequest("NEW456", false, null));

        verify(mergeService, never()).merge(any(), any(), any());
        assertEquals(target.getId(), joiner.getHousehold().getId());
    }

    @Test
    void join_notifiesExistingMembersButNotTheJoiner() {
        var ownHome = household("OLD123");
        var joiner = hostOf(ownHome, "Carla");
        var target = household("NEW456");
        var host = hostOf(target, "Ana");
        when(householdRepository.findByInviteCode("NEW456")).thenReturn(Optional.of(target));
        members(ownHome, joiner);
        members(target, host);

        householdService.join(joiner, new JoinHouseholdRequest("NEW456", null, null));

        var captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService).notifyAfterCommit(captor.capture());
        assertEquals(host, captor.getValue().user());
        assertEquals(NotificationType.HOUSEHOLD_MEMBER_JOINED, captor.getValue().type());
        assertEquals("household.member.joined.title", captor.getValue().title());
    }

    @Test
    void join_whileGuestElsewhere_departsThatHouseFirst() {
        // Bruno is a guest at Ana's; he joins Carla's. He must leave Ana's by the rules
        // (back to origin with restore + copy) and only then merge from his origin.
        var brunoOrigin = household("BRU001");
        var anas = household("ANA001");
        var ana = hostOf(anas, "Ana");
        var bruno = user("Bruno", anas, brunoOrigin, LocalDateTime.now().minusDays(3));
        var carlas = household("CAR001");
        var carla = hostOf(carlas, "Carla");
        when(householdRepository.findByInviteCode("CAR001")).thenReturn(Optional.of(carlas));
        members(anas, ana, bruno);
        members(carlas, carla);
        originStillExists(brunoOrigin);

        householdService.join(bruno, new JoinHouseholdRequest("CAR001", null, null));

        verify(mergeService).restoreOriginals(brunoOrigin);
        verify(mergeService).copyGeneratedSince(eq(anas), eq(brunoOrigin), any(LocalDateTime.class));
        verify(mergeService).merge(eq(brunoOrigin), eq(carlas), any());
        assertEquals(carlas.getId(), bruno.getHousehold().getId());
        // Ana is told he left, Carla is told he joined.
        var captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService, org.mockito.Mockito.times(2)).notifyAfterCommit(captor.capture());
        assertEquals(NotificationType.HOUSEHOLD_MEMBER_LEFT, captor.getAllValues().get(0).type());
        assertEquals(ana, captor.getAllValues().get(0).user());
        assertEquals(NotificationType.HOUSEHOLD_MEMBER_JOINED, captor.getAllValues().get(1).type());
        assertEquals(carla, captor.getAllValues().get(1).user());
    }

    @Test
    void join_refusesWhenHouseholdIsFull() {
        var ownHome = household("OLD123");
        var joiner = hostOf(ownHome, "Bruno");
        var target = household("NEW456");
        var memberA = user("A", target, target, LONG_AGO);
        var memberB = user("B", target, household("B0001"), LONG_AGO);
        when(householdRepository.findByInviteCode("NEW456")).thenReturn(Optional.of(target));
        members(target, memberA, memberB);
        when(subscriptionGate.householdMemberCap(anyList()))
                .thenReturn(new SubscriptionGateService.MemberCap(2, true));
        var request = new JoinHouseholdRequest("NEW456", null, null);

        var thrown = assertThrows(HouseholdFullException.class, () -> householdService.join(joiner, request));

        assertTrue(thrown.isUpgradeAvailable());
        assertEquals(ownHome.getId(), joiner.getHousehold().getId());
        verify(userRepository, never()).save(any());
        verify(mergeService, never()).merge(any(), any(), any());
    }

    @Test
    void join_capCountsTheJoinerWhenAskingTheGate() {
        var ownHome = household("OLD123");
        var joiner = hostOf(ownHome, "Bruno");
        var target = household("NEW456");
        var host = hostOf(target, "Ana");
        when(householdRepository.findByInviteCode("NEW456")).thenReturn(Optional.of(target));
        members(ownHome, joiner);
        members(target, host);

        householdService.join(joiner, new JoinHouseholdRequest("NEW456", null, null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<User>> everyone = ArgumentCaptor.forClass(List.class);
        verify(subscriptionGate).householdMemberCap(everyone.capture());
        assertTrue(everyone.getValue().contains(joiner));
        assertTrue(everyone.getValue().contains(host));
    }

    @Test
    void join_throwsForInvalidCode() {
        var home = household("ABC123");
        var user = hostOf(home, "Ana");
        when(householdRepository.findByInviteCode("XYZ999")).thenReturn(Optional.empty());
        var request = new JoinHouseholdRequest("XYZ999", null, null);

        assertThrows(InvalidInviteCodeException.class, () -> householdService.join(user, request));
    }

    @Test
    void join_throwsWhenAlreadyInTargetHousehold() {
        var home = household("ABC123");
        var user = hostOf(home, "Ana");
        when(householdRepository.findByInviteCode("ABC123")).thenReturn(Optional.of(home));
        var request = new JoinHouseholdRequest("ABC123", null, null);

        assertThrows(AlreadyInHouseholdException.class, () -> householdService.join(user, request));
    }

    // ---------- leave: guest ----------

    @Test
    void leave_hostAloneAtHome_isRefused() {
        var home = household("SOLO01");
        var host = hostOf(home, "Ana");
        members(home, host);

        assertThrows(SoloHouseholdLeaveException.class, () -> householdService.leave(host));

        assertEquals(home.getId(), host.getHousehold().getId());
        verify(userRepository, never()).save(any());
    }

    @Test
    void leave_guest_returnsToOriginRestoresBroughtDataAndCopiesWhatWasGeneratedSinceJoining() {
        var anas = household("ANA001");
        var ana = hostOf(anas, "Ana");
        var brunoOrigin = household("BRU001");
        var joinedAt = LocalDateTime.now().minusDays(10);
        var bruno = user("Bruno", anas, brunoOrigin, joinedAt);
        members(anas, ana, bruno);
        originStillExists(brunoOrigin);

        var response = householdService.leave(bruno);

        assertEquals(brunoOrigin.getId(), bruno.getHousehold().getId());
        assertEquals(brunoOrigin.getId(), response.id());
        assertEquals(bruno.getId(), response.hostId(), "owner of the origin is healed to Bruno");
        verify(mergeService).restoreOriginals(brunoOrigin);
        verify(mergeService).copyGeneratedSince(anas, brunoOrigin, joinedAt);
        assertEquals(ana, anas.getOwner(), "Ana stays host");
        var captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService).notifyAfterCommit(captor.capture());
        assertEquals(ana, captor.getValue().user());
        assertEquals(NotificationType.HOUSEHOLD_MEMBER_LEFT, captor.getValue().type());
    }

    @Test
    void leave_guestWhoseOriginIsGone_getsFreshHouseholdThatBecomesTheirOrigin() {
        var anas = household("ANA001");
        var ana = hostOf(anas, "Ana");
        var bruno = user("Bruno", anas, null, LocalDateTime.now().minusDays(2));
        members(anas, ana, bruno);

        var response = householdService.leave(bruno);

        assertNotEquals(anas.getId(), bruno.getHousehold().getId());
        assertEquals(bruno.getHousehold(), bruno.getOriginHousehold());
        assertEquals(bruno.getId(), response.hostId());
        verify(mergeService).copyGeneratedSince(eq(anas), eq(bruno.getHousehold()), any(LocalDateTime.class));
    }

    // ---------- leave: host ----------

    @Test
    void leave_originalHost_getsNewHouseholdWithCopyOfEverythingAndOldestGuestInherits() {
        var home = household("ANA001");
        var ana = hostOf(home, "Ana");
        var older = user("Older", home, household("OLD001"), LocalDateTime.now().minusDays(30));
        var newer = user("Newer", home, household("NEW001"), LocalDateTime.now().minusDays(1));
        members(home, ana, newer, older);

        var response = householdService.leave(ana);

        assertNotEquals(home.getId(), ana.getHousehold().getId());
        assertEquals(ana.getHousehold(), ana.getOriginHousehold(), "the new household is Ana's origin now");
        assertEquals(ana.getId(), response.hostId());
        verify(mergeService).copyGeneratedSince(home, ana.getHousehold(), null);
        verify(mergeService, never()).restoreOriginals(any());
        assertEquals(older, home.getOwner(), "oldest guest becomes host");
    }

    @Test
    void leave_promotedHost_returnsToOriginLikeAGuestAndPassesHostOn() {
        var home = household("ANA001");               // Ana left long ago; Bruno was promoted
        var brunoOrigin = household("BRU001");
        var bruno = user("Bruno", home, brunoOrigin, LocalDateTime.now().minusDays(20));
        home.setOwner(bruno);
        var carla = user("Carla", home, household("CAR001"), LocalDateTime.now().minusDays(5));
        members(home, bruno, carla);
        originStillExists(brunoOrigin);

        householdService.leave(bruno);

        assertEquals(brunoOrigin.getId(), bruno.getHousehold().getId());
        verify(mergeService).restoreOriginals(brunoOrigin);
        verify(mergeService).copyGeneratedSince(eq(home), eq(brunoOrigin), any(LocalDateTime.class));
        assertEquals(carla, home.getOwner());
    }

    // ---------- leave: last member ----------

    @Test
    void leave_lastMemberNotAtOrigin_evacuatesDataHomeAndDeletesTheEmptyHousehold() {
        var anas = household("ANA001");              // Ana already left; Bruno is alone here
        var brunoOrigin = household("BRU001");
        var bruno = user("Bruno", anas, brunoOrigin, LocalDateTime.now().minusDays(7));
        anas.setOwner(bruno);
        members(anas, bruno);
        originStillExists(brunoOrigin);
        when(userRepository.countByHouseholdId(anas.getId())).thenReturn(0L);
        when(userRepository.existsByOriginHouseholdId(anas.getId())).thenReturn(false);
        when(receiptRepository.existsByHouseholdId(anas.getId())).thenReturn(false);
        when(receiptRepository.existsByOriginHouseholdId(anas.getId())).thenReturn(false);

        var response = householdService.leave(bruno);

        assertEquals(brunoOrigin.getId(), response.id());
        verify(mergeService).evacuate(anas, brunoOrigin);
        verify(mergeService, never()).copyGeneratedSince(any(), any(), any());
        verify(householdRepository).delete(anas);
    }

    @Test
    void leave_lastMember_keepsHouseholdWhenItIsStillSomeonesOrigin() {
        var anas = household("ANA001");
        var brunoOrigin = household("BRU001");
        var bruno = user("Bruno", anas, brunoOrigin, LocalDateTime.now().minusDays(7));
        members(anas, bruno);
        originStillExists(brunoOrigin);
        when(userRepository.countByHouseholdId(anas.getId())).thenReturn(0L);
        when(userRepository.existsByOriginHouseholdId(anas.getId())).thenReturn(true);   // Ana calls it home

        householdService.leave(bruno);

        verify(mergeService).evacuate(anas, brunoOrigin);
        verify(householdRepository, never()).delete(anas);
    }

    @Test
    void leave_sendsNoNotificationWhenNobodyIsLeftBehind() {
        var anas = household("ANA001");
        var brunoOrigin = household("BRU001");
        var bruno = user("Bruno", anas, brunoOrigin, LocalDateTime.now().minusDays(7));
        members(anas, bruno);
        originStillExists(brunoOrigin);
        when(userRepository.countByHouseholdId(anas.getId())).thenReturn(0L);
        when(userRepository.existsByOriginHouseholdId(anas.getId())).thenReturn(true);

        householdService.leave(bruno);

        verify(notificationService, never()).notifyAfterCommit(any());
        verify(mergeService, never()).copyGeneratedSince(any(), any(), isNull());
    }
}
