package com.relyon.economizaai.service.admin;

import com.relyon.economizaai.exception.AdminRoleChangeException;
import com.relyon.economizaai.exception.AdminUserDeletionException;
import com.relyon.economizaai.exception.UserNotFoundException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.model.enums.PaidApiService;
import com.relyon.economizaai.dto.response.AdminUserSummaryResponse;
import com.relyon.economizaai.model.enums.SubscriptionTier;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.repository.InsightsRepository;
import com.relyon.economizaai.repository.MerchantAccessRepository;
import com.relyon.economizaai.repository.PaidApiCallRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.UserService;
import com.relyon.economizaai.service.subscription.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private ReceiptRepository receiptRepository;
    @Mock private InsightsRepository insightsRepository;
    @Mock private SubscriptionService subscriptionService;
    @Mock private UserService userService;
    @Mock private MerchantAccessRepository merchantAccessRepository;
    @Mock private PaidApiCallRepository paidApiCallRepository;

    @InjectMocks private AdminUserService service;

    @Test
    void costBreakdownForUser_sumsTotalAndKeepsServiceLines() {
        var userId = UUID.randomUUID();
        when(paidApiCallRepository.costByServiceForUser(userId)).thenReturn(List.of(
                serviceSpend(PaidApiService.INFOSIMPLES, 10, 240),
                serviceSpend(PaidApiService.CAPTCHA_SOLVE, 5, 15)));

        var result = service.costBreakdownForUser(userId);

        assertEquals(255L, result.totalCents());
        assertEquals(2, result.byService().size());
        assertEquals(PaidApiService.INFOSIMPLES, result.byService().get(0).service());
    }

    @Test
    void totalCostCentsByUserIds_emptyInput_returnsEmptyMapWithoutQuerying() {
        assertTrue(service.totalCostCentsByUserIds(List.of()).isEmpty());
        verify(paidApiCallRepository, never()).totalCostByUserIds(anyList());
    }

    @Test
    void totalCostCentsByUserIds_mapsRowsByUser() {
        var userId = UUID.randomUUID();
        when(paidApiCallRepository.totalCostByUserIds(anyList())).thenReturn(List.of(userCost(userId, 777)));

        var map = service.totalCostCentsByUserIds(List.of(userId));

        assertEquals(777L, map.get(userId));
    }

    private static PaidApiCallRepository.UserServiceSpend serviceSpend(PaidApiService service, long calls, long cents) {
        return new PaidApiCallRepository.UserServiceSpend() {
            public PaidApiService getService() { return service; }
            public long getCalls() { return calls; }
            public long getCostCents() { return cents; }
        };
    }

    private static PaidApiCallRepository.UserCost userCost(UUID userId, long cents) {
        return new PaidApiCallRepository.UserCost() {
            public UUID getUserId() { return userId; }
            public long getCostCents() { return cents; }
        };
    }

    @Test
    void delete_regularUser_delegatesToDeleteAccount() {
        var user = User.builder().id(UUID.randomUUID()).name("Test").email("garbage@test.com")
                .role(Role.USER).build();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        service.delete(user.getId());

        verify(userService).deleteAccount(user);
    }

    @Test
    void delete_adminAccount_refused() {
        var admin = User.builder().id(UUID.randomUUID()).name("Admin").email("admin@test.com")
                .role(Role.ADMIN).build();
        when(userRepository.findById(admin.getId())).thenReturn(Optional.of(admin));

        assertThrows(AdminUserDeletionException.class, () -> service.delete(admin.getId()));
        verify(userService, never()).deleteAccount(any());
    }

    @Test
    void delete_unknownUser_throwsNotFound() {
        var unknownId = UUID.randomUUID();
        when(userRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThrows(UserNotFoundException.class, () -> service.delete(unknownId));
    }

    @Test
    void setTierToPro_activatesAndReturnsDetail() {
        var householdId = UUID.randomUUID();
        var household = Household.builder().id(householdId).inviteCode("ABC123").build();
        var user = User.builder().id(UUID.randomUUID()).name("Maria").email("maria@test.com")
                .household(household).build();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        stubEmptyDetailAggregates(householdId, user.getId());

        var detail = service.setTier(user.getId(), SubscriptionTier.PRO);

        verify(subscriptionService).activatePro(user, "manual", null, null);
        assertEquals("maria@test.com", detail.email());
    }

    @Test
    void setTierToFree_cancels() {
        var householdId = UUID.randomUUID();
        var household = Household.builder().id(householdId).inviteCode("ABC123").build();
        var user = User.builder().id(UUID.randomUUID()).name("Joao").email("joao@test.com")
                .household(household).build();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        stubEmptyDetailAggregates(householdId, user.getId());

        service.setTier(user.getId(), SubscriptionTier.FREE);

        verify(subscriptionService).cancel(user);
    }

    @Test
    void setTierThrowsWhenUserMissing() {
        var id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());
        assertThrows(UserNotFoundException.class, () -> service.setTier(id, SubscriptionTier.PRO));
    }

    @Test
    void setMetricsExclusion_setsFlagSavesAndReflectsInDetail() {
        var householdId = UUID.randomUUID();
        var household = Household.builder().id(householdId).inviteCode("ABC123").build();
        var user = User.builder().id(UUID.randomUUID()).name("Reviewer Test").email("testreviewer123@gmail.com")
                .household(household).build();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        stubEmptyDetailAggregates(householdId, user.getId());

        var detail = service.setMetricsExclusion(user.getId(), true);

        assertTrue(user.isExcludedFromMetrics());
        assertTrue(detail.excludedFromMetrics());
        verify(userRepository).save(user);
    }

    @Test
    void setRole_promoteToMerchant_savesAndReturnsDetail() {
        var householdId = UUID.randomUUID();
        var household = Household.builder().id(householdId).inviteCode("ABC123").build();
        var user = User.builder().id(UUID.randomUUID()).name("Mercado Teste").email("merchant@economizaai.app")
                .household(household).role(Role.USER).build();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        stubEmptyDetailAggregates(householdId, user.getId());

        service.setRole(user.getId(), Role.MERCHANT);

        assertEquals(Role.MERCHANT, user.getRole());
        verify(userRepository).save(user);
        verify(merchantAccessRepository, never()).deleteAllByUserId(any());
    }

    @Test
    void setRole_demoteMerchant_revokesChainGrants() {
        var householdId = UUID.randomUUID();
        var household = Household.builder().id(householdId).inviteCode("ABC123").build();
        var user = User.builder().id(UUID.randomUUID()).name("Ex Mercado").email("exmerchant@economizaai.app")
                .household(household).role(Role.MERCHANT).build();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        stubEmptyDetailAggregates(householdId, user.getId());

        service.setRole(user.getId(), Role.USER);

        assertEquals(Role.USER, user.getRole());
        verify(merchantAccessRepository).deleteAllByUserId(user.getId());
    }

    @Test
    void setRole_touchingAdmin_refused() {
        var admin = User.builder().id(UUID.randomUUID()).name("Admin").email("admin@test.com")
                .role(Role.ADMIN).build();
        var regular = User.builder().id(UUID.randomUUID()).name("User").email("user@test.com")
                .role(Role.USER).build();
        when(userRepository.findById(admin.getId())).thenReturn(Optional.of(admin));
        when(userRepository.findById(regular.getId())).thenReturn(Optional.of(regular));

        assertThrows(AdminRoleChangeException.class, () -> service.setRole(admin.getId(), Role.USER));
        assertThrows(AdminRoleChangeException.class, () -> service.setRole(regular.getId(), Role.ADMIN));
        verify(userRepository, never()).save(any());
    }

    @Test
    void getBundlesUserWithHouseholdAndSpendStats() {
        var householdId = UUID.randomUUID();
        var household = Household.builder().id(householdId).inviteCode("ABC123").build();
        var user = User.builder()
                .id(UUID.randomUUID())
                .name("Maria")
                .email("maria@test.com")
                .household(household)
                .build();

        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        // USER-OWN status breakdown (one batched query returning (status, count) rows).
        when(receiptRepository.statusBreakdownByUserId(user.getId())).thenReturn(List.<Object[]>of(
                new Object[]{ReceiptStatus.PENDING_CONFIRMATION, 2L},
                new Object[]{ReceiptStatus.CONFIRMED, 7L},
                new Object[]{ReceiptStatus.FAILED_PARSE, 1L}));
        when(receiptRepository.sumConfirmedTotalByUserSince(eq(user.getId()), any(LocalDateTime.class)))
                .thenReturn(new BigDecimal("321.45"));
        // HOUSEHOLD context, kept separate.
        when(receiptRepository.countByHouseholdId(householdId)).thenReturn(12L);
        when(insightsRepository.totalSpend(eq(householdId), any(LocalDateTime.class), any(LocalDateTime.class), any(), any()))
                .thenReturn(new BigDecimal("999.00"));
        when(userRepository.countByHouseholdId(householdId)).thenReturn(2L);

        var detail = service.get(user.getId());

        assertEquals("Maria", detail.name());
        assertEquals(householdId, detail.householdId());
        assertEquals(2L, detail.householdMemberCount());
        // USER-OWN numbers.
        assertEquals(7L, detail.receipts().confirmed());
        assertEquals(2L, detail.receipts().pendingConfirmation());
        assertEquals(1L, detail.receipts().failedParse());
        assertEquals(0, new BigDecimal("321.45").compareTo(detail.spendLast30Days()));
        // HOUSEHOLD totals, separate and not conflated.
        assertEquals(12L, detail.householdReceiptCount());
        assertEquals(0, new BigDecimal("999.00").compareTo(detail.householdSpend()));
    }

    @Test
    void getThrowsWhenUserMissing() {
        var id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());
        assertThrows(UserNotFoundException.class, () -> service.get(id));
    }

    @Test
    void getNullSpendFallsBackToZero() {
        var householdId = UUID.randomUUID();
        var household = Household.builder().id(householdId).inviteCode("ABC123").build();
        var user = User.builder()
                .id(UUID.randomUUID())
                .name("Joao")
                .email("joao@test.com")
                .household(household)
                .build();

        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(receiptRepository.statusBreakdownByUserId(user.getId())).thenReturn(List.of());
        when(receiptRepository.sumConfirmedTotalByUserSince(eq(user.getId()), any(LocalDateTime.class)))
                .thenReturn(null);
        when(receiptRepository.countByHouseholdId(householdId)).thenReturn(0L);
        when(insightsRepository.totalSpend(eq(householdId), any(LocalDateTime.class), any(LocalDateTime.class), any(), any()))
                .thenReturn(null);
        when(userRepository.countByHouseholdId(householdId)).thenReturn(1L);

        var detail = service.get(user.getId());

        assertEquals(BigDecimal.ZERO, detail.spendLast30Days());
        assertEquals(BigDecimal.ZERO, detail.householdSpend());
    }

    @Test
    void listUnsortedPageableDefaultsToCreatedAtDescAndMapsSummaries() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        var user = User.builder()
                .id(UUID.randomUUID())
                .name("John Doe")
                .email("john@test.com")
                .household(household)
                .build();
        user.setCreatedAt(LocalDateTime.now());
        var sortedPageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(user)));

        var page = service.list(null, null, null, null, null, null, false, PageRequest.of(0, 20));

        assertEquals(1, page.getTotalElements());
        assertEquals("john@test.com", page.getContent().get(0).email());

        verify(userRepository).findAll(any(Specification.class), sortedPageableCaptor.capture());
        var appliedSort = sortedPageableCaptor.getValue().getSort().getOrderFor("createdAt");
        assertTrue(appliedSort != null && appliedSort.getDirection() == Sort.Direction.DESC);
    }

    @Test
    void listEnrichesUserOwnReceiptCountAndHouseholdBadge() {
        var householdId = UUID.randomUUID();
        var household = Household.builder().id(householdId).inviteCode("ABC123").build();
        var user = User.builder().id(UUID.randomUUID()).name("Ana").email("ana@test.com").household(household).build();
        user.setCreatedAt(LocalDateTime.now());
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(user)));
        // USER-OWN receipt count (keyed by user_id), NOT the household total.
        when(receiptRepository.countByUserIds(List.of(user.getId())))
                .thenReturn(List.<Object[]>of(new Object[]{user.getId(), 7L}));
        // HOUSEHOLD totals + member count, separate.
        when(receiptRepository.countByHouseholdIds(List.of(householdId)))
                .thenReturn(List.<Object[]>of(new Object[]{householdId, 40L}));
        when(userRepository.countByHouseholdIds(List.of(householdId)))
                .thenReturn(List.<Object[]>of(new Object[]{householdId, 3L}));

        var page = service.list(null, null, null, null, null, null, false, PageRequest.of(0, 20));

        var summary = page.getContent().get(0);
        assertEquals(7L, summary.receiptCount());            // user-own
        assertEquals(40L, summary.householdReceiptCount());  // household total, not conflated
        assertEquals(3L, summary.householdMemberCount());    // "Domicílio 3" badge
    }

    @Test
    void listPreservesCallerSortWhenAlreadySorted() {
        var requested = PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "email"));
        var sortedPageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.list("  john  ", null, null, null, null, null, false, requested);

        verify(userRepository).findAll(any(Specification.class), sortedPageableCaptor.capture());
        assertEquals(requested, sortedPageableCaptor.getValue());
    }

    @Test
    void listRanksByUserOwnSpendDescWhenSortIsTotalSpend() {
        var poor = User.builder().id(UUID.randomUUID()).name("Poor").email("poor@test.com")
                .household(Household.builder().id(UUID.randomUUID()).inviteCode("P").build()).build();
        var rich = User.builder().id(UUID.randomUUID()).name("Rich").email("rich@test.com")
                .household(Household.builder().id(UUID.randomUUID()).inviteCode("R").build()).build();
        poor.setCreatedAt(LocalDateTime.now().minusDays(1));
        rich.setCreatedAt(LocalDateTime.now());
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(poor, rich)));
        // Ranked by the USER's own spend (keyed by user_id), not the household's.
        when(receiptRepository.sumConfirmedTotalByUserIds(anyList())).thenReturn(List.<Object[]>of(
                new Object[]{poor.getId(), new BigDecimal("10.00")},
                new Object[]{rich.getId(), new BigDecimal("999.00")}));

        var requested = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "totalSpend"));
        var page = service.list(null, null, null, null, null, null, false, requested);

        assertEquals("rich@test.com", page.getContent().get(0).email());
        assertEquals(new BigDecimal("999.00"), page.getContent().get(0).totalSpend());
        assertEquals("poor@test.com", page.getContent().get(1).email());
    }

    @Test
    void listRanksByUserOwnReceiptCountAndPaginatesInMemory() {
        var userA = User.builder().id(UUID.randomUUID()).name("A").email("a@test.com")
                .household(Household.builder().id(UUID.randomUUID()).inviteCode("A").build()).build();
        var userB = User.builder().id(UUID.randomUUID()).name("B").email("b@test.com")
                .household(Household.builder().id(UUID.randomUUID()).inviteCode("B").build()).build();
        userA.setCreatedAt(LocalDateTime.now());
        userB.setCreatedAt(LocalDateTime.now());
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(userA, userB)));
        // Ranked by the USER's own receipt count (keyed by user_id).
        when(receiptRepository.countByUserIds(anyList())).thenReturn(List.<Object[]>of(
                new Object[]{userA.getId(), 2L}, new Object[]{userB.getId(), 40L}));

        var page = service.list(null, null, null, null, null, null, false, PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "receiptCount")));

        assertEquals(2, page.getTotalElements());
        assertEquals(1, page.getContent().size());
        assertEquals("b@test.com", page.getContent().get(0).email()); // 40 notas ranks first
    }

    @Test
    void listSortsByPredominantStateAndKeepsUsersWithoutStateLast() {
        var fromSaoPaulo = userInOwnHousehold("sp@test.com");
        var fromBahia = userInOwnHousehold("ba@test.com");
        var withoutNotes = userInOwnHousehold("none@test.com");
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(fromSaoPaulo, withoutNotes, fromBahia)));
        when(receiptRepository.ufFrequencyByHouseholdIds(anyList())).thenReturn(List.<Object[]>of(
                new Object[]{fromSaoPaulo.getHousehold().getId(), UnidadeFederativa.SP, 4L},
                new Object[]{fromBahia.getHousehold().getId(), UnidadeFederativa.BA, 2L}));

        var ascending = service.list(null, null, null, null, null, null, false,
                PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "uf")));
        var descending = service.list(null, null, null, null, null, null, false,
                PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "uf")));

        assertEquals(List.of("ba@test.com", "sp@test.com", "none@test.com"),
                ascending.getContent().stream().map(AdminUserSummaryResponse::email).toList());
        assertEquals(List.of("sp@test.com", "ba@test.com", "none@test.com"),
                descending.getContent().stream().map(AdminUserSummaryResponse::email).toList());
    }

    private static User userInOwnHousehold(String email) {
        var user = User.builder().id(UUID.randomUUID()).name(email).email(email)
                .household(Household.builder().id(UUID.randomUUID()).inviteCode(email).build()).build();
        user.setCreatedAt(LocalDateTime.now());
        return user;
    }

    /** Stub the detail-view aggregates to empty/zero — for mutation tests that don't assert counts. */
    private void stubEmptyDetailAggregates(UUID householdId, UUID userId) {
        when(receiptRepository.statusBreakdownByUserId(userId)).thenReturn(List.of());
        when(receiptRepository.sumConfirmedTotalByUserSince(eq(userId), any(LocalDateTime.class)))
                .thenReturn(BigDecimal.ZERO);
        when(receiptRepository.countByHouseholdId(householdId)).thenReturn(0L);
        when(insightsRepository.totalSpend(eq(householdId), any(LocalDateTime.class), any(LocalDateTime.class), any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(userRepository.countByHouseholdId(householdId)).thenReturn(1L);
    }
}
