package com.relyon.economizaai.repository;

import com.relyon.economizaai.dto.request.JoinHouseholdRequest;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.service.HouseholdMergeService;
import com.relyon.economizaai.service.HouseholdService;
import com.relyon.economizaai.service.HouseholdSplitService;
import com.relyon.economizaai.service.ReceiptRawHtmlService;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.cache.HouseholdCacheGen;
import com.relyon.economizaai.service.notifications.NotificationService;
import com.relyon.economizaai.service.subscription.SubscriptionGateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Join/leave through the real service and database with a DETACHED user — exactly what
 * production hands the service (the authenticated principal was loaded in another
 * session, so its household references are uninitialized proxies). Mocked unit tests
 * can't see LazyInitializationException; this does.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({HouseholdService.class, HouseholdMergeService.class, HouseholdSplitService.class,
        ReceiptRawHtmlService.class})
class HouseholdMembershipIntegrationTest {

    @Autowired private HouseholdService householdService;
    @Autowired private HouseholdRepository householdRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ReceiptRepository receiptRepository;
    @Autowired private TestEntityManager entityManager;

    @MockitoBean private HouseholdCacheGen householdCacheGen;
    @MockitoBean private SubscriptionGateService subscriptionGate;
    @MockitoBean private NotificationService notificationService;
    @MockitoBean private LocalizedMessageService messageService;

    private UUID hostId;
    private UUID guestId;
    private UUID sharedHouseholdId;
    private UUID guestHomeId;
    private String inviteCode;

    @BeforeEach
    void setUp() {
        when(subscriptionGate.householdMemberCap(anyList())).thenReturn(new SubscriptionGateService.MemberCap(6, false));
        when(messageService.translate(anyString(), any(Locale.class), any())).thenReturn("text");

        var shared = householdRepository.save(Household.builder().inviteCode("SHRD01")
                .inviteCodeExpiresAt(LocalDateTime.now().plusHours(48)).build());
        var guestHome = householdRepository.save(Household.builder().inviteCode("GHOM01")
                .inviteCodeExpiresAt(LocalDateTime.now().plusHours(48)).build());
        var host = saveUser("host-membership@economizaai.app", shared);
        var guest = saveUser("guest-membership@economizaai.app", guestHome);
        shared.setOwner(host);
        guestHome.setOwner(guest);
        seedReceipt(guest, guestHome, "GUEST_OLD");
        seedReceipt(host, shared, "HOST_OLD");
        hostId = host.getId();
        guestId = guest.getId();
        sharedHouseholdId = shared.getId();
        guestHomeId = guestHome.getId();
        inviteCode = shared.getInviteCode();
        detachEverything();
    }

    private User saveUser(String email, Household household) {
        return userRepository.save(User.builder()
                .name(email).email(email).password("x").household(household)
                .acceptedTermsVersion("1.0").acceptedPrivacyVersion("1.0")
                .acceptedLegalAt(LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0))
                .build());
    }

    private void seedReceipt(User scanner, Household household, String chave) {
        receiptRepository.save(Receipt.builder()
                .user(scanner).household(household).originHousehold(household)
                .chaveAcesso(chave).uf(UnidadeFederativa.RS).cnpjEmitente("12345678000190")
                .issuedAt(LocalDateTime.of(2026, Month.MAY, 1, 12, 0)).totalAmount(new BigDecimal("10.00"))
                .qrPayload("payload").status(ReceiptStatus.CONFIRMED)
                .build());
    }

    private void detachEverything() {
        entityManager.flush();
        entityManager.clear();
    }

    // Like the JWT filter: load the user, then let the session go.
    private User principal(UUID userId) {
        var user = userRepository.findById(userId).orElseThrow();
        entityManager.clear();
        return user;
    }

    private void guestJoins(boolean bringData) {
        householdService.join(principal(guestId), new JoinHouseholdRequest(inviteCode, bringData, null));
        detachEverything();
    }

    @Test
    void join_withDetachedPrincipal_movesTheGuestAndKeepsTheirHome() {
        guestJoins(true);

        var guest = userRepository.findById(guestId).orElseThrow();
        assertEquals(sharedHouseholdId, guest.getHousehold().getId());
        assertEquals(guestHomeId, guest.getHomeHousehold().getId());
        assertEquals(2, receiptRepository.findAllByHouseholdId(sharedHouseholdId).size());
        assertFalse(householdRepository.findById(guestHomeId).orElseThrow()
                .getInviteCodeExpiresAt().isAfter(LocalDateTime.now()));
    }

    @Test
    void guestLeaves_returnsHomeWithWhatTheyBroughtAndACopy() {
        guestJoins(true);
        seedReceipt(userRepository.findById(hostId).orElseThrow(),
                householdRepository.findById(sharedHouseholdId).orElseThrow(), "HOST_NEW");
        detachEverything();

        householdService.leave(principal(guestId));
        detachEverything();

        var guest = userRepository.findById(guestId).orElseThrow();
        assertEquals(guestHomeId, guest.getHousehold().getId());
        assertNull(guest.getHomeHousehold());
        var homeChaves = receiptRepository.findAllByHouseholdId(guestHomeId).stream()
                .map(Receipt::getChaveAcesso).sorted().toList();
        assertEquals(List.of("GUEST_OLD", "HOST_NEW"), homeChaves);
        assertEquals(2, receiptRepository.findAllByHouseholdId(sharedHouseholdId).size(), "the household keeps its own");
    }

    @Test
    void originalHostLeaves_newHouseholdWithEverythingAndGuestPromoted() {
        guestJoins(true);

        var response = householdService.leave(principal(hostId));
        detachEverything();

        assertNotEquals(sharedHouseholdId, response.id());
        assertEquals(2, receiptRepository.findAllByHouseholdId(response.id()).size());
        var shared = householdRepository.findById(sharedHouseholdId).orElseThrow();
        assertEquals(guestId, shared.getOwner().getId());
    }

    @Test
    void lastMemberLeaves_householdMovesHomeAndIsDeleted() {
        guestJoins(false);
        householdService.leave(principal(hostId));
        detachEverything();

        householdService.leave(principal(guestId));
        detachEverything();

        assertFalse(householdRepository.existsById(sharedHouseholdId));
        var guest = userRepository.findById(guestId).orElseThrow();
        assertEquals(guestHomeId, guest.getHousehold().getId());
        var homeChaves = receiptRepository.findAllByHouseholdId(guestHomeId).stream()
                .map(Receipt::getChaveAcesso).sorted().toList();
        assertEquals(List.of("GUEST_OLD", "HOST_OLD"), homeChaves);
    }
}
