package com.relyon.economizaai.service;

import com.relyon.economizaai.dto.request.JoinHouseholdRequest;
import com.relyon.economizaai.exception.AlreadyInHouseholdException;
import com.relyon.economizaai.exception.HouseholdFullException;
import com.relyon.economizaai.exception.HouseholdLeaveFirstException;
import com.relyon.economizaai.exception.InvalidInviteCodeException;
import com.relyon.economizaai.exception.NotHouseholdHostException;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
        // Unit tests run without Spring, so @Value never fires: the production default is ON.
        ReflectionTestUtils.setField(householdService, "mergeEnabled", true);
        // Room for everyone unless a test says otherwise; translations echo their key.
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
        lenient().when(mergeService.merge(any(), any(), any()))
                .thenReturn(new HouseholdMergeService.MergeResult(0, 0));
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

    private Household buildHousehold(String code) {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode(code).build();
        household.setCreatedAt(LocalDateTime.now());
        household.setUpdatedAt(LocalDateTime.now());
        lenient().when(householdRepository.findById(household.getId())).thenReturn(Optional.of(household));
        return household;
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

    // ---------- regenerate invite ----------

    @Test
    void regenerateInviteCode_onlyHostMay() {
        var household = buildHousehold("ABC123");
        var host = buildUser(household, "Host", LocalDateTime.now().minusDays(5));
        var guest = asGuest(buildUser(household, "Guest", LocalDateTime.now()), buildHousehold("HOME01"));
        household.setOwner(host);
        when(householdRepository.findById(household.getId())).thenReturn(Optional.of(household));
        when(userRepository.findAllByHouseholdId(household.getId())).thenReturn(List.of(host, guest));

        assertThrows(NotHouseholdHostException.class, () -> householdService.regenerateInviteCode(guest));

        lenient().when(householdRepository.existsByInviteCode(any())).thenReturn(false);
        var response = householdService.regenerateInviteCode(host);
        assertNotEquals("ABC123", response.inviteCode());
    }

    @Test
    void getMine_hidesInviteCodeFromGuests() {
        var household = buildHousehold("ABC123");
        var host = buildUser(household, "Host", LocalDateTime.now().minusDays(5));
        var guest = asGuest(buildUser(household, "Guest", LocalDateTime.now()), buildHousehold("HOME01"));
        household.setOwner(host);
        when(householdRepository.findById(household.getId())).thenReturn(Optional.of(household));
        when(userRepository.findAllByHouseholdId(household.getId())).thenReturn(List.of(host, guest));

        var guestView = householdService.getMine(guest);
        var hostView = householdService.getMine(host);

        assertNull(guestView.inviteCode());
        assertEquals("ABC123", hostView.inviteCode());
        assertTrue(guestView.canLeave());
    }

    @Test
    void getMine_aloneAtHomeCannotLeave() {
        var household = buildHousehold("ABC123");
        var user = buildUser(household);
        household.setOwner(user);
        when(householdRepository.findById(household.getId())).thenReturn(Optional.of(household));
        when(userRepository.findAllByHouseholdId(household.getId())).thenReturn(List.of(user));

        assertFalse(householdService.getMine(user).canLeave());
    }

    // ---------- join ----------

    @Test
    void join_movesUserRecordsHomeAndJoinTimeAndKeepsHomeHousehold() {
        var previous = buildHousehold("OLD123");
        var target = buildHousehold("NEW456");
        var user = buildUser(previous);
        var host = buildUser(target, "Host", LocalDateTime.now().minusDays(5));
        target.setOwner(host);
        stubJoin(target, previous, user, List.of(host));
        when(userRepository.countByHouseholdId(previous.getId())).thenReturn(0L);
        when(userRepository.existsByHomeHouseholdId(previous.getId())).thenReturn(true);

        var response = householdService.join(user, new JoinHouseholdRequest("new456", null, null));

        assertEquals(target.getId(), user.getHousehold().getId());
        assertEquals(previous, user.getHomeHousehold());
        assertNotNull(user.getHouseholdJoinedAt());
        assertEquals(target.getId(), response.id());
        assertNull(response.inviteCode(), "a guest never sees the invite code");
        assertTrue(previous.getInviteCodeExpiresAt().isBefore(LocalDateTime.now().plusSeconds(1)),
                "the home's invite code is expired while its owner is away");
        verify(mergeService).merge(previous, target, Set.of(MergeCategory.values()));
        verify(householdCacheGen).bump(previous.getId());
        verify(householdCacheGen).bump(target.getId());
        verify(householdRepository, never()).delete(previous);
    }

    @Test
    void join_refusedWhileStillSharingAHousehold() {
        var previous = buildHousehold("OLD123");
        var target = buildHousehold("NEW456");
        var user = buildUser(previous);
        var partner = buildUser(previous, "Partner", LocalDateTime.now());
        when(householdRepository.findByInviteCode("NEW456")).thenReturn(Optional.of(target));
        when(userRepository.findAllByHouseholdId(previous.getId())).thenReturn(List.of(user, partner));
        var request = new JoinHouseholdRequest("NEW456", null, null);

        assertThrows(HouseholdLeaveFirstException.class, () -> householdService.join(user, request));

        assertEquals(previous.getId(), user.getHousehold().getId());
        verify(userRepository, never()).save(any());
    }

    @Test
    void join_aloneAwayFromHome_goesHomeWithEverythingFirst() {
        var home = buildHousehold("HOME01");
        var stranded = buildHousehold("OLD123");
        var target = buildHousehold("NEW456");
        var user = asGuest(buildUser(stranded), home);
        when(householdRepository.findByInviteCode("NEW456")).thenReturn(Optional.of(target));
        lenient().when(userRepository.findAllByHouseholdId(stranded.getId())).thenReturn(List.of(user));
        lenient().when(userRepository.findAllByHouseholdId(target.getId())).thenReturn(List.of());

        householdService.join(user, new JoinHouseholdRequest("NEW456", false, null));

        verify(splitService).absorbInto(stranded, home);
        assertEquals(target.getId(), user.getHousehold().getId());
        assertEquals(home, user.getHomeHousehold(), "home is where they came back to, not the absorbed household");
    }

    @Test
    void join_notifiesExistingMembersButNotTheJoiner() {
        var previous = buildHousehold("OLD123");
        var target = buildHousehold("NEW456");
        var user = buildUser(previous, "Carla", LocalDateTime.now());
        var host = buildUser(target, "Host", LocalDateTime.now().minusDays(5));
        stubJoin(target, previous, user, List.of(host));

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
        stubJoin(target, previous, user, List.of());

        householdService.join(user, new JoinHouseholdRequest("NEW456", false, null));

        verify(mergeService, never()).merge(any(), any(), any());
    }

    @Test
    void join_skipsMergeWhenFeatureDisabled() {
        ReflectionTestUtils.setField(householdService, "mergeEnabled", false);
        var previous = buildHousehold("OLD123");
        var target = buildHousehold("NEW456");
        var user = buildUser(previous);
        stubJoin(target, previous, user, List.of());

        householdService.join(user, new JoinHouseholdRequest("NEW456", true, null));

        verify(mergeService, never()).merge(any(), any(), any());
    }

    @Test
    void join_refusesWhenHouseholdIsFull() {
        var previous = buildHousehold("OLD123");
        var target = buildHousehold("NEW456");
        var user = buildUser(previous);
        var memberA = buildUser(target, "A", LocalDateTime.now());
        var memberB = buildUser(target, "B", LocalDateTime.now());
        stubJoin(target, previous, user, List.of(memberA, memberB));
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
        stubJoin(target, previous, user, List.of(host));

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
    void leave_refusedWhenAloneInOwnHome() {
        var household = buildHousehold("SOLO01");
        var user = buildUser(household);
        when(userRepository.findAllByHouseholdId(household.getId())).thenReturn(List.of(user));

        assertThrows(SoloHouseholdLeaveException.class, () -> householdService.leave(user));

        assertEquals(household.getId(), user.getHousehold().getId());
        verify(userRepository, never()).save(any());
    }

    @Test
    void leave_lastMemberAwayFromHome_absorbsHouseholdIntoHome() {
        var home = buildHousehold("HOME01");
        var shared = buildHousehold("FAM111");
        var lastMember = asGuest(buildUser(shared), home);
        when(userRepository.findAllByHouseholdId(shared.getId())).thenReturn(List.of(lastMember));
        lenient().when(userRepository.findAllByHouseholdId(home.getId())).thenReturn(List.of(lastMember));

        var response = householdService.leave(lastMember);

        assertEquals(home.getId(), lastMember.getHousehold().getId());
        assertNull(lastMember.getHomeHousehold());
        assertNull(lastMember.getHouseholdJoinedAt());
        assertEquals(home.getId(), response.id());
        verify(mergeService).restoreOriginals(home);
        verify(splitService).absorbInto(shared, home);
        verify(splitService, never()).copyInto(any(), any(), any());
    }

    @Test
    void leave_guestReturnsHomeRestoresWhatTheyBroughtAndCopiesSinceJoin() {
        var shared = buildHousehold("FAM111");
        var home = buildHousehold("OLD123");
        var stayer = buildUser(shared, "Stayer", LocalDateTime.now().minusDays(3));
        var leaver = asGuest(buildUser(shared, "Leaver", LocalDateTime.now()), home);
        var joinedAt = leaver.getHouseholdJoinedAt();
        shared.setOwner(stayer);
        when(userRepository.findAllByHouseholdId(shared.getId())).thenReturn(List.of(leaver, stayer));
        lenient().when(userRepository.findAllByHouseholdId(home.getId())).thenReturn(List.of(leaver));
        when(userRepository.countByHouseholdId(shared.getId())).thenReturn(1L);

        var response = householdService.leave(leaver);

        assertEquals(home.getId(), leaver.getHousehold().getId());
        assertNull(leaver.getHomeHousehold());
        assertEquals(home.getId(), response.id());
        assertEquals(leaver.getId(), response.hostId(), "leaver hosts the home they return to");
        assertEquals(stayer, shared.getOwner(), "the shared household keeps its host");
        verify(mergeService).restoreOriginals(home);
        verify(splitService).copyInto(shared, home, joinedAt);
        verify(householdCacheGen).bump(shared.getId());
        verify(householdCacheGen).bump(home.getId());
        var captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService).notifyAfterCommit(captor.capture());
        assertEquals(stayer, captor.getValue().user());
        assertEquals(NotificationType.HOUSEHOLD_MEMBER_LEFT, captor.getValue().type());
    }

    @Test
    void leave_originalHost_startsNewHouseholdWithFullCopyAndOldestGuestInherits() {
        var shared = buildHousehold("FAM111");
        var host = buildUser(shared, "Host", LocalDateTime.now().minusDays(30));
        var firstGuest = asGuest(buildUser(shared, "First", LocalDateTime.now()), buildHousehold("HOME01"));
        firstGuest.setHouseholdJoinedAt(LocalDateTime.now().minusDays(20));
        var laterGuest = asGuest(buildUser(shared, "Later", LocalDateTime.now().minusDays(90)), buildHousehold("HOME02"));
        laterGuest.setHouseholdJoinedAt(LocalDateTime.now().minusDays(2));
        shared.setOwner(host);
        when(userRepository.findAllByHouseholdId(shared.getId())).thenReturn(List.of(host, laterGuest, firstGuest));
        when(householdRepository.existsByInviteCode(any())).thenReturn(false);
        when(userRepository.countByHouseholdId(shared.getId())).thenReturn(2L);

        var response = householdService.leave(host);

        assertNotEquals(shared.getId(), host.getHousehold().getId());
        assertEquals(host.getId(), response.hostId());
        assertEquals(firstGuest, shared.getOwner(), "the guest who joined first inherits, not the oldest account");
        verify(splitService).copyInto(shared, host.getHousehold(), null);
        verify(mergeService, never()).restoreOriginals(any());
    }

    @Test
    void leave_promotedHostGoesBackToTheirOwnHome() {
        var shared = buildHousehold("FAM111");
        var home = buildHousehold("HOME01");
        var promoted = asGuest(buildUser(shared, "Promoted", LocalDateTime.now()), home);
        var other = asGuest(buildUser(shared, "Other", LocalDateTime.now()), buildHousehold("HOME02"));
        shared.setOwner(promoted);
        when(userRepository.findAllByHouseholdId(shared.getId())).thenReturn(List.of(promoted, other));
        lenient().when(userRepository.findAllByHouseholdId(home.getId())).thenReturn(List.of(promoted));
        when(userRepository.countByHouseholdId(shared.getId())).thenReturn(1L);

        householdService.leave(promoted);

        assertEquals(home.getId(), promoted.getHousehold().getId());
        verify(splitService).copyInto(eq(shared), eq(home), any());
        assertEquals(other, shared.getOwner());
    }

    @Test
    void leave_mergeDisabled_hostStillGetsFreshHouseholdWithoutRestore() {
        ReflectionTestUtils.setField(householdService, "mergeEnabled", false);
        var previous = buildHousehold("OLD123");
        var user = buildUser(previous);
        var other = asGuest(buildUser(previous, "Other", LocalDateTime.now()), buildHousehold("HOME01"));
        when(userRepository.findAllByHouseholdId(previous.getId())).thenReturn(List.of(user, other));
        when(householdRepository.existsByInviteCode(any())).thenReturn(false);
        when(userRepository.countByHouseholdId(previous.getId())).thenReturn(1L);

        householdService.leave(user);

        assertNotEquals(previous.getId(), user.getHousehold().getId());
        verify(mergeService, never()).restoreOriginals(any());
        assertFalse(user.getHousehold().getInviteCode().isBlank());
    }

    // ---------- helpers ----------

    private User asGuest(User user, Household home) {
        user.setHomeHousehold(home);
        user.setHouseholdJoinedAt(LocalDateTime.now().minusDays(1));
        return user;
    }

    private void stubJoin(Household target, Household previous, User joiner, List<User> targetMembers) {
        when(householdRepository.findByInviteCode("NEW456")).thenReturn(Optional.of(target));
        lenient().when(userRepository.findAllByHouseholdId(previous.getId())).thenReturn(List.of(joiner));
        var afterJoin = new ArrayList<>(targetMembers);
        afterJoin.add(joiner);
        lenient().when(userRepository.findAllByHouseholdId(target.getId()))
                .thenReturn(targetMembers)
                .thenReturn(afterJoin);
    }
}
