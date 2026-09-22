package com.relyon.economizaai.service.admin;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.InsightsRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.repository.UserRepository;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Branch coverage beyond {@link AdminUserServiceTest}: drives the search
 * {@link Specification} predicate-building both with and without a search term
 * by capturing the spec and invoking it against a mocked criteria graph.
 */
@ExtendWith(MockitoExtension.class)
class AdminUserServiceCoverageTest {

    @Mock private UserRepository userRepository;
    @Mock private ReceiptRepository receiptRepository;
    @Mock private InsightsRepository insightsRepository;

    @InjectMocks private AdminUserService service;

    @SuppressWarnings("unchecked")
    private Predicate runSpec(Specification<User> spec) {
        var root = (Root<User>) org.mockito.Mockito.mock(Root.class);
        var query = (CriteriaQuery<?>) org.mockito.Mockito.mock(CriteriaQuery.class);
        var cb = org.mockito.Mockito.mock(CriteriaBuilder.class);

        var emailPath = (Path<String>) org.mockito.Mockito.mock(Path.class);
        var namePath = (Path<String>) org.mockito.Mockito.mock(Path.class);
        var loweredEmail = (Expression<String>) org.mockito.Mockito.mock(Expression.class);
        var loweredName = (Expression<String>) org.mockito.Mockito.mock(Expression.class);
        var likeEmail = org.mockito.Mockito.mock(Predicate.class);
        var likeName = org.mockito.Mockito.mock(Predicate.class);
        var orPredicate = org.mockito.Mockito.mock(Predicate.class);
        var andPredicate = org.mockito.Mockito.mock(Predicate.class);

        lenient().when(root.<String>get("email")).thenReturn(emailPath);
        lenient().when(root.<String>get("name")).thenReturn(namePath);
        lenient().when(cb.lower(emailPath)).thenReturn(loweredEmail);
        lenient().when(cb.lower(namePath)).thenReturn(loweredName);
        lenient().when(cb.like(eq(loweredEmail), anyString())).thenReturn(likeEmail);
        lenient().when(cb.like(eq(loweredName), anyString())).thenReturn(likeName);
        lenient().when(cb.or(any(Predicate.class), any(Predicate.class))).thenReturn(orPredicate);
        lenient().when(cb.and(any(Predicate[].class))).thenReturn(andPredicate);

        return spec.toPredicate(root, query, cb);
    }

    @Test
    void searchSpec_withTerm_buildsNonNullPredicate() {
        var specCaptor = ArgumentCaptor.forClass(Specification.class);
        when(userRepository.findAll(specCaptor.capture(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.list("Maria", PageRequest.of(0, 10));

        @SuppressWarnings("unchecked")
        var spec = (Specification<User>) specCaptor.getValue();
        var predicate = runSpec(spec);
        assertNotNull(predicate);
    }

    @Test
    void searchSpec_withTerm_usesPercentWrappedLowercaseLike() {
        var cb = org.mockito.Mockito.mock(CriteriaBuilder.class);
        @SuppressWarnings("unchecked")
        var root = (Root<User>) org.mockito.Mockito.mock(Root.class);
        var query = (CriteriaQuery<?>) org.mockito.Mockito.mock(CriteriaQuery.class);

        @SuppressWarnings("unchecked")
        var emailPath = (Path<String>) org.mockito.Mockito.mock(Path.class);
        @SuppressWarnings("unchecked")
        var namePath = (Path<String>) org.mockito.Mockito.mock(Path.class);
        @SuppressWarnings("unchecked")
        var loweredEmail = (Expression<String>) org.mockito.Mockito.mock(Expression.class);
        @SuppressWarnings("unchecked")
        var loweredName = (Expression<String>) org.mockito.Mockito.mock(Expression.class);
        var like = org.mockito.Mockito.mock(Predicate.class);
        var or = org.mockito.Mockito.mock(Predicate.class);
        var and = org.mockito.Mockito.mock(Predicate.class);

        lenient().when(root.<String>get("email")).thenReturn(emailPath);
        lenient().when(root.<String>get("name")).thenReturn(namePath);
        lenient().when(cb.lower(emailPath)).thenReturn(loweredEmail);
        lenient().when(cb.lower(namePath)).thenReturn(loweredName);
        when(cb.like(any(Expression.class), anyString())).thenReturn(like);
        when(cb.or(any(Predicate.class), any(Predicate.class))).thenReturn(or);
        when(cb.and(any(Predicate[].class))).thenReturn(and);

        var specCaptor = ArgumentCaptor.forClass(Specification.class);
        when(userRepository.findAll(specCaptor.capture(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        service.list("Bob", PageRequest.of(0, 10));
        @SuppressWarnings("unchecked")
        var spec = (Specification<User>) specCaptor.getValue();

        spec.toPredicate(root, query, cb);

        // term lowercased and percent-wrapped on both columns
        verify(cb).like(eq(loweredEmail), contains("%bob%"));
        verify(cb).like(eq(loweredName), contains("%bob%"));
        verify(cb).or(like, like);
    }

    @Test
    void searchSpec_blankSearch_normalizesToNull_andSkipsLikePredicate() {
        var cb = org.mockito.Mockito.mock(CriteriaBuilder.class);
        @SuppressWarnings("unchecked")
        var root = (Root<User>) org.mockito.Mockito.mock(Root.class);
        var query = (CriteriaQuery<?>) org.mockito.Mockito.mock(CriteriaQuery.class);
        var and = org.mockito.Mockito.mock(Predicate.class);
        when(cb.and(any(Predicate[].class))).thenReturn(and);

        var specCaptor = ArgumentCaptor.forClass(Specification.class);
        when(userRepository.findAll(specCaptor.capture(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        // "   " is blank → trimmed to null → no like predicate built.
        service.list("   ", PageRequest.of(0, 10));
        @SuppressWarnings("unchecked")
        var spec = (Specification<User>) specCaptor.getValue();

        spec.toPredicate(root, query, cb);

        verify(cb, never()).like(any(Expression.class), anyString());
        verify(cb, never()).or(any(Predicate.class), any(Predicate.class));
        verify(cb).and(any(Predicate[].class));
    }

    @Test
    void searchSpec_nullSearch_skipsLikePredicate() {
        var cb = org.mockito.Mockito.mock(CriteriaBuilder.class);
        @SuppressWarnings("unchecked")
        var root = (Root<User>) org.mockito.Mockito.mock(Root.class);
        var query = (CriteriaQuery<?>) org.mockito.Mockito.mock(CriteriaQuery.class);
        var and = org.mockito.Mockito.mock(Predicate.class);
        when(cb.and(any(Predicate[].class))).thenReturn(and);

        var specCaptor = ArgumentCaptor.forClass(Specification.class);
        when(userRepository.findAll(specCaptor.capture(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        service.list(null, PageRequest.of(0, 10));
        @SuppressWarnings("unchecked")
        var spec = (Specification<User>) specCaptor.getValue();

        spec.toPredicate(root, query, cb);

        verify(cb, never()).like(any(Expression.class), anyString());
    }

    @Test
    void get_detailReceiptCountsMapEachStatusToItsField() {
        var householdId = java.util.UUID.randomUUID();
        var household = Household.builder().id(householdId).inviteCode("ZZ9999").build();
        var user = User.builder()
                .id(java.util.UUID.randomUUID()).name("Ana").email("ana@test.com").household(household).build();

        when(userRepository.findById(user.getId())).thenReturn(java.util.Optional.of(user));
        when(receiptRepository.countByHouseholdIdAndStatus(householdId, ReceiptStatus.PENDING_CONFIRMATION)).thenReturn(1L);
        when(receiptRepository.countByHouseholdIdAndStatus(householdId, ReceiptStatus.CONFIRMED)).thenReturn(2L);
        when(receiptRepository.countByHouseholdIdAndStatus(householdId, ReceiptStatus.REJECTED)).thenReturn(3L);
        when(receiptRepository.countByHouseholdIdAndStatus(householdId, ReceiptStatus.FAILED_PARSE)).thenReturn(4L);
        when(insightsRepository.totalSpend(eq(householdId), any(), any())).thenReturn(new java.math.BigDecimal("99.00"));
        when(userRepository.countByHouseholdId(householdId)).thenReturn(5L);

        var detail = service.get(user.getId());

        org.junit.jupiter.api.Assertions.assertEquals(1L, detail.receipts().pendingConfirmation());
        org.junit.jupiter.api.Assertions.assertEquals(2L, detail.receipts().confirmed());
        org.junit.jupiter.api.Assertions.assertEquals(3L, detail.receipts().rejected());
        org.junit.jupiter.api.Assertions.assertEquals(4L, detail.receipts().failedParse());
        org.junit.jupiter.api.Assertions.assertEquals(5L, detail.householdMemberCount());
        verify(receiptRepository, times(4)).countByHouseholdIdAndStatus(eq(householdId), any(ReceiptStatus.class));
    }
}
