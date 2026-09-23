package com.relyon.economizaai.service.admin;

import com.relyon.economizaai.exception.AdminUserDeletionException;
import com.relyon.economizaai.exception.UserNotFoundException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.model.enums.SubscriptionTier;
import com.relyon.economizaai.repository.InsightsRepository;
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

    @InjectMocks private AdminUserService service;

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
        when(receiptRepository.countByHouseholdIdAndStatus(eq(householdId), any(ReceiptStatus.class))).thenReturn(0L);
        when(insightsRepository.totalSpend(eq(householdId), any(LocalDateTime.class), any(LocalDateTime.class), any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(userRepository.countByHouseholdId(householdId)).thenReturn(1L);

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
        when(receiptRepository.countByHouseholdIdAndStatus(eq(householdId), any(ReceiptStatus.class))).thenReturn(0L);
        when(insightsRepository.totalSpend(eq(householdId), any(LocalDateTime.class), any(LocalDateTime.class), any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(userRepository.countByHouseholdId(householdId)).thenReturn(1L);

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
        when(receiptRepository.countByHouseholdIdAndStatus(eq(householdId), any(ReceiptStatus.class))).thenReturn(0L);
        when(insightsRepository.totalSpend(eq(householdId), any(LocalDateTime.class), any(LocalDateTime.class), any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(userRepository.countByHouseholdId(householdId)).thenReturn(1L);

        var detail = service.setMetricsExclusion(user.getId(), true);

        assertTrue(user.isExcludedFromMetrics());
        assertTrue(detail.excludedFromMetrics());
        verify(userRepository).save(user);
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
        when(receiptRepository.countByHouseholdIdAndStatus(householdId, ReceiptStatus.PENDING_CONFIRMATION)).thenReturn(2L);
        when(receiptRepository.countByHouseholdIdAndStatus(householdId, ReceiptStatus.CONFIRMED)).thenReturn(7L);
        when(receiptRepository.countByHouseholdIdAndStatus(householdId, ReceiptStatus.REJECTED)).thenReturn(0L);
        when(receiptRepository.countByHouseholdIdAndStatus(householdId, ReceiptStatus.FAILED_PARSE)).thenReturn(1L);
        when(insightsRepository.totalSpend(eq(householdId), any(LocalDateTime.class), any(LocalDateTime.class), any(), any()))
                .thenReturn(new BigDecimal("321.45"));
        when(userRepository.countByHouseholdId(householdId)).thenReturn(2L);

        var detail = service.get(user.getId());

        assertEquals("Maria", detail.name());
        assertEquals(householdId, detail.householdId());
        assertEquals(2L, detail.householdMemberCount());
        assertEquals(7L, detail.receipts().confirmed());
        assertEquals(2L, detail.receipts().pendingConfirmation());
        assertEquals(0, new BigDecimal("321.45").compareTo(detail.spendLast30Days()));
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
        when(receiptRepository.countByHouseholdIdAndStatus(eq(householdId), any(ReceiptStatus.class))).thenReturn(0L);
        when(insightsRepository.totalSpend(eq(householdId), any(LocalDateTime.class), any(LocalDateTime.class), any(), any()))
                .thenReturn(null);
        when(userRepository.countByHouseholdId(householdId)).thenReturn(1L);

        var detail = service.get(user.getId());

        assertEquals(BigDecimal.ZERO, detail.spendLast30Days());
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

        var page = service.list(null, false, PageRequest.of(0, 20));

        assertEquals(1, page.getTotalElements());
        assertEquals("john@test.com", page.getContent().get(0).email());

        verify(userRepository).findAll(any(Specification.class), sortedPageableCaptor.capture());
        var appliedSort = sortedPageableCaptor.getValue().getSort().getOrderFor("createdAt");
        assertTrue(appliedSort != null && appliedSort.getDirection() == Sort.Direction.DESC);
    }

    @Test
    void listEnrichesReceiptCountPerHousehold() {
        var householdId = UUID.randomUUID();
        var household = Household.builder().id(householdId).inviteCode("ABC123").build();
        var user = User.builder().id(UUID.randomUUID()).name("Ana").email("ana@test.com").household(household).build();
        user.setCreatedAt(LocalDateTime.now());
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(user)));
        when(receiptRepository.countByHouseholdIds(List.of(householdId)))
                .thenReturn(List.<Object[]>of(new Object[]{householdId, 7L}));

        var page = service.list(null, false, PageRequest.of(0, 20));

        assertEquals(7L, page.getContent().get(0).receiptCount());
    }

    @Test
    void listPreservesCallerSortWhenAlreadySorted() {
        var requested = PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "email"));
        var sortedPageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.list("  john  ", false, requested);

        verify(userRepository).findAll(any(Specification.class), sortedPageableCaptor.capture());
        assertEquals(requested, sortedPageableCaptor.getValue());
    }

    @Test
    void listRanksBySpendDescWhenSortIsTotalSpend() {
        var poorHome = UUID.randomUUID();
        var richHome = UUID.randomUUID();
        var poor = User.builder().id(UUID.randomUUID()).name("Poor").email("poor@test.com")
                .household(Household.builder().id(poorHome).inviteCode("P").build()).build();
        var rich = User.builder().id(UUID.randomUUID()).name("Rich").email("rich@test.com")
                .household(Household.builder().id(richHome).inviteCode("R").build()).build();
        poor.setCreatedAt(LocalDateTime.now().minusDays(1));
        rich.setCreatedAt(LocalDateTime.now());
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(poor, rich)));
        when(receiptRepository.sumConfirmedTotalByHouseholdIds(anyList())).thenReturn(List.<Object[]>of(
                new Object[]{poorHome, new BigDecimal("10.00")},
                new Object[]{richHome, new BigDecimal("999.00")}));

        var requested = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "totalSpend"));
        var page = service.list(null, false, requested);

        assertEquals("rich@test.com", page.getContent().get(0).email());
        assertEquals(new BigDecimal("999.00"), page.getContent().get(0).totalSpend());
        assertEquals("poor@test.com", page.getContent().get(1).email());
    }

    @Test
    void listRanksByReceiptCountAndPaginatesInMemory() {
        var homeA = UUID.randomUUID();
        var homeB = UUID.randomUUID();
        var userA = User.builder().id(UUID.randomUUID()).name("A").email("a@test.com")
                .household(Household.builder().id(homeA).inviteCode("A").build()).build();
        var userB = User.builder().id(UUID.randomUUID()).name("B").email("b@test.com")
                .household(Household.builder().id(homeB).inviteCode("B").build()).build();
        userA.setCreatedAt(LocalDateTime.now());
        userB.setCreatedAt(LocalDateTime.now());
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(userA, userB)));
        when(receiptRepository.countByHouseholdIds(anyList())).thenReturn(List.<Object[]>of(
                new Object[]{homeA, 2L}, new Object[]{homeB, 40L}));

        var page = service.list(null, false, PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "receiptCount")));

        assertEquals(2, page.getTotalElements());
        assertEquals(1, page.getContent().size());
        assertEquals("b@test.com", page.getContent().get(0).email()); // 40 notas ranks first
    }
}
