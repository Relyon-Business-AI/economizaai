package com.relyon.economizaai.service;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.service.ItemQueryService.ItemFilters;
import com.relyon.economizaai.service.geo.MarketNameService;
import com.relyon.economizaai.service.subscription.SubscriptionGateService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-mock unit test for {@link ItemQueryService}: drives the dynamic-JPQL
 * count/row flow with a mocked {@link EntityManager}, asserting clause shaping,
 * the count==0 short-circuit, pagination wiring, and override-label mapping.
 * The H2 end-to-end behavior lives in {@code ItemQueryServiceIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class ItemQueryServiceTest {

    @Mock private HouseholdProductCategoryOverrideService categoryOverrideService;
    @Mock private MarketNameService marketNameService;
    @Mock private SubscriptionGateService subscriptionGate;
    @Mock private EntityManager entityManager;
    @Mock private TypedQuery<Long> countQuery;
    @Mock private TypedQuery<ReceiptItem> rowQuery;

    private ItemQueryService service;
    private UUID householdId;
    private User user;

    @BeforeEach
    void setUp() {
        service = new ItemQueryService(categoryOverrideService, marketNameService, subscriptionGate);
        // Default: PRO passthrough (no clamping) so existing clause assertions hold.
        lenient().when(subscriptionGate.clampFrom(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        lenient().when(marketNameService.resolveNames(any(), any())).thenReturn(Map.of());
        lenient().when(marketNameService.applyOverride(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        householdId = UUID.randomUUID();
        var household = Household.builder().id(householdId).inviteCode("ABC123").build();
        user = User.builder().id(UUID.randomUUID()).email("u@e.test").household(household).build();
    }

    private ItemFilters emptyFilters() {
        return ItemFilters.fromRequest(null, null, null, null, null, null, null, null, null);
    }

    private ReceiptItem linkedItem(Product product, BigDecimal totalPrice) {
        var receipt = Receipt.builder()
                .id(UUID.randomUUID())
                .cnpjEmitente("93015006005182")
                .marketName("Mercado X")
                .issuedAt(LocalDateTime.of(2026, Month.MAY, 1, 10, 0))
                .build();
        var item = ReceiptItem.builder()
                .id(UUID.randomUUID())
                .lineNumber(1)
                .rawDescription("ARROZ")
                .quantity(BigDecimal.ONE)
                .unit("UN")
                .unitPrice(totalPrice)
                .totalPrice(totalPrice)
                .product(product)
                .build();
        receipt.addItem(item);
        return item;
    }

    @Test
    void query_countZero_shortCircuitsAndReturnsEmptyPageWithoutRowQuery() {
        when(entityManager.createQuery(anyString(), eq(Long.class))).thenReturn(countQuery);
        when(countQuery.getSingleResult()).thenReturn(0L);

        var page = service.query(user, emptyFilters(), PageRequest.of(0, 20));

        assertEquals(0, page.getTotalElements());
        assertTrue(page.getContent().isEmpty());
        verify(entityManager, never()).createQuery(anyString(), eq(ReceiptItem.class));
        verify(categoryOverrideService, never()).overridesByProduct(any(), any());
    }

    @Test
    void query_withRows_mapsToResponseAndAppliesOverrideLabel() {
        var product = Product.builder().id(UUID.randomUUID()).normalizedName("arroz")
                .category(ProductCategory.GROCERIES).build();
        var item = linkedItem(product, new BigDecimal("12.50"));

        when(entityManager.createQuery(anyString(), eq(Long.class))).thenReturn(countQuery);
        when(countQuery.getSingleResult()).thenReturn(1L);
        when(entityManager.createQuery(anyString(), eq(ReceiptItem.class))).thenReturn(rowQuery);
        when(rowQuery.getResultList()).thenReturn(List.of(item));
        when(categoryOverrideService.overridesByProduct(householdId, List.of(product.getId())))
                .thenReturn(Map.of(product.getId(), "Minha Categoria"));

        var page = service.query(user, emptyFilters(), PageRequest.of(0, 20));

        assertEquals(1, page.getTotalElements());
        var row = page.getContent().get(0);
        assertEquals(product.getId(), row.productId());
        assertEquals("Minha Categoria", row.category());
        assertEquals(0, row.totalPrice().compareTo(new BigDecimal("12.50")));
        verify(rowQuery).setFirstResult(0);
        verify(rowQuery).setMaxResults(20);
    }

    @Test
    void query_unlinkedItem_usesGlobalCategoryNullAndNullProductId() {
        var receipt = Receipt.builder()
                .id(UUID.randomUUID()).cnpjEmitente("93015006005182").marketName("M")
                .issuedAt(LocalDateTime.now()).build();
        var unlinked = ReceiptItem.builder()
                .id(UUID.randomUUID()).lineNumber(1).rawDescription("UNKNOWN")
                .quantity(BigDecimal.ONE).unitPrice(BigDecimal.ONE).totalPrice(BigDecimal.ONE)
                .build();
        receipt.addItem(unlinked);

        when(entityManager.createQuery(anyString(), eq(Long.class))).thenReturn(countQuery);
        when(countQuery.getSingleResult()).thenReturn(1L);
        when(entityManager.createQuery(anyString(), eq(ReceiptItem.class))).thenReturn(rowQuery);
        when(rowQuery.getResultList()).thenReturn(List.of(unlinked));
        // No products → empty productIds list passed to override lookup.
        when(categoryOverrideService.overridesByProduct(householdId, List.of()))
                .thenReturn(Map.of());

        var page = service.query(user, emptyFilters(), PageRequest.of(0, 20));

        var row = page.getContent().get(0);
        assertNull(row.productId());
        assertNull(row.category());
    }

    @Test
    void query_paginationOffsetUsesPageableOffset() {
        var product = Product.builder().id(UUID.randomUUID()).normalizedName("x").build();
        var item = linkedItem(product, new BigDecimal("3.00"));

        when(entityManager.createQuery(anyString(), eq(Long.class))).thenReturn(countQuery);
        when(countQuery.getSingleResult()).thenReturn(25L);
        when(entityManager.createQuery(anyString(), eq(ReceiptItem.class))).thenReturn(rowQuery);
        when(rowQuery.getResultList()).thenReturn(List.of(item));
        when(categoryOverrideService.overridesByProduct(any(), any())).thenReturn(Map.of());

        service.query(user, emptyFilters(), PageRequest.of(2, 5));

        verify(rowQuery).setFirstResult(10); // page 2 * size 5
        verify(rowQuery).setMaxResults(5);
    }

    @Test
    void query_allOptionalFiltersBound_includesEveryClauseParameter() {
        var product = Product.builder().id(UUID.randomUUID()).normalizedName("x").build();
        var item = linkedItem(product, new BigDecimal("3.00"));
        var productId = UUID.randomUUID();
        var fullFilters = ItemFilters.fromRequest(
                LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0),
                LocalDateTime.of(2026, Month.DECEMBER, 31, 23, 59),
                List.of("93015006005182"),
                List.of("93015006"),
                List.of(ProductCategory.GROCERIES),
                List.of(productId),
                List.of("7891234567890"),
                new BigDecimal("10.00"),
                new BigDecimal("500.00"));

        var capturedCountJpql = ArgumentCaptor.forClass(String.class);
        when(entityManager.createQuery(capturedCountJpql.capture(), eq(Long.class))).thenReturn(countQuery);
        when(countQuery.getSingleResult()).thenReturn(1L);
        when(entityManager.createQuery(anyString(), eq(ReceiptItem.class))).thenReturn(rowQuery);
        when(rowQuery.getResultList()).thenReturn(List.of(item));
        when(categoryOverrideService.overridesByProduct(any(), any())).thenReturn(Map.of());

        service.query(user, fullFilters, PageRequest.of(0, 20));

        var jpql = capturedCountJpql.getValue();
        assertTrue(jpql.contains("r.cnpjEmitente IN (:marketCnpjs)"));
        assertTrue(jpql.contains("SUBSTRING(r.cnpjEmitente, 1, 8) IN (:marketCnpjRoots)"));
        assertTrue(jpql.contains("COALESCE(ri.categoryAtConfirmation, p.category) IN (:categories)"));
        assertTrue(jpql.contains("p.id IN (:productIds)"));
        assertTrue(jpql.contains("ri.ean IN (:eans)"));
        assertTrue(jpql.contains("r.totalAmount >= :minReceiptTotal"));
        assertTrue(jpql.contains("r.totalAmount <= :maxReceiptTotal"));
        // Every binding above plus the four base bindings must be set on the count query.
        verify(countQuery).setParameter(eq("marketCnpjs"), any());
        verify(countQuery).setParameter(eq("marketCnpjRoots"), any());
        verify(countQuery).setParameter(eq("categories"), any());
        verify(countQuery).setParameter(eq("productIds"), any());
        verify(countQuery).setParameter(eq("eans"), any());
        verify(countQuery).setParameter(eq("minReceiptTotal"), any());
        verify(countQuery).setParameter(eq("maxReceiptTotal"), any());
        verify(countQuery).setParameter("householdId", householdId);
    }

    @Test
    void query_blankStringFiltersNormalizeToNull_noOptionalClauses() {
        var product = Product.builder().id(UUID.randomUUID()).normalizedName("x").build();
        var item = linkedItem(product, new BigDecimal("3.00"));
        // Empty/blank string lists must normalize to null → no IN-clause emitted.
        var filters = ItemFilters.fromRequest(null, null,
                List.of("  ", ""), List.of(), null, null, List.of("   "), null, null);

        var capturedCountJpql = ArgumentCaptor.forClass(String.class);
        when(entityManager.createQuery(capturedCountJpql.capture(), eq(Long.class))).thenReturn(countQuery);
        when(countQuery.getSingleResult()).thenReturn(1L);
        when(entityManager.createQuery(anyString(), eq(ReceiptItem.class))).thenReturn(rowQuery);
        when(rowQuery.getResultList()).thenReturn(List.of(item));
        when(categoryOverrideService.overridesByProduct(any(), any())).thenReturn(Map.of());

        service.query(user, filters, PageRequest.of(0, 20));

        var jpql = capturedCountJpql.getValue();
        assertFalse(jpql.contains("r.cnpjEmitente IN (:marketCnpjs)"));
        assertFalse(jpql.contains("ri.ean IN (:eans)"));
        verify(countQuery, never()).setParameter(eq("marketCnpjs"), any());
        verify(countQuery, never()).setParameter(eq("eans"), any());
    }

    @Test
    void filters_fromRequest_carriesNullHouseholdIdUntilNormalized() {
        var filters = ItemFilters.fromRequest(null, null, null, null, null, null, null, null, null);
        assertNull(filters.householdId());
    }

    @Test
    void query_multipleRowsWithMixedProducts_distinctProductIdsCollected() {
        var productA = Product.builder().id(UUID.randomUUID()).normalizedName("a")
                .category(ProductCategory.GROCERIES).build();
        var itemA1 = linkedItem(productA, new BigDecimal("2.00"));
        var itemA2 = linkedItem(productA, new BigDecimal("2.50")); // same product twice → distinct collapses
        var receipt = Receipt.builder().id(UUID.randomUUID()).cnpjEmitente("c").marketName("m")
                .issuedAt(LocalDateTime.now()).build();
        var unlinked = ReceiptItem.builder().id(UUID.randomUUID()).lineNumber(1).rawDescription("u")
                .quantity(BigDecimal.ONE).unitPrice(BigDecimal.ONE).totalPrice(BigDecimal.ONE).build();
        receipt.addItem(unlinked);

        when(entityManager.createQuery(anyString(), eq(Long.class))).thenReturn(countQuery);
        when(countQuery.getSingleResult()).thenReturn(3L);
        when(entityManager.createQuery(anyString(), eq(ReceiptItem.class))).thenReturn(rowQuery);
        when(rowQuery.getResultList()).thenReturn(List.of(itemA1, itemA2, unlinked));
        var capturedIds = ArgumentCaptor.forClass(List.class);
        when(categoryOverrideService.overridesByProduct(eq(householdId), capturedIds.capture()))
                .thenReturn(Map.of());

        var page = service.query(user, emptyFilters(), PageRequest.of(0, 20));

        assertEquals(3, page.getContent().size());
        assertEquals(1, capturedIds.getValue().size()); // productA only, distinct
        assertEquals(productA.getId(), capturedIds.getValue().get(0));
    }
}
