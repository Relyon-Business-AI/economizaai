package com.relyon.economizaai.service;

import com.relyon.economizaai.exception.ProductNotFoundException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.ProductRecentView;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.ProductRecentViewRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.HouseholdProductAliasService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductRecentViewServiceTest {

    @Mock private ProductRecentViewRepository recentViewRepository;
    @Mock private ProductRepository productRepository;
    @Mock private HouseholdProductAliasService householdProductAliasService;
    @InjectMocks private ProductRecentViewService service;

    private User user() {
        return User.builder()
                .id(UUID.randomUUID())
                .email("user@test.com")
                .household(Household.builder().id(UUID.randomUUID()).build())
                .build();
    }

    private Product product(UUID id) {
        return Product.builder()
                .normalizedName("LEITE ITALAC 1L")
                .category(ProductCategory.MEAT_DAIRY)
                .categorizationSource(CategorizationSource.DICTIONARY)
                .build();
    }

    @Test
    void track_newView_createsEntry() {
        var user = user();
        var productId = UUID.randomUUID();
        var product = product(productId);
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(recentViewRepository.findByUserIdAndProductId(user.getId(), productId)).thenReturn(Optional.empty());

        service.track(user, productId);

        var captor = ArgumentCaptor.forClass(ProductRecentView.class);
        verify(recentViewRepository).save(captor.capture());
        assertEquals(user, captor.getValue().getUser());
        assertEquals(product, captor.getValue().getProduct());
        assertNotNull(captor.getValue().getViewedAt());
    }

    @Test
    void track_existingView_updatesViewedAt() {
        var user = user();
        var productId = UUID.randomUUID();
        var product = product(productId);
        var existing = ProductRecentView.builder()
                .user(user)
                .product(product)
                .viewedAt(LocalDateTime.now().minusDays(3))
                .build();
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(recentViewRepository.findByUserIdAndProductId(user.getId(), productId)).thenReturn(Optional.of(existing));

        service.track(user, productId);

        var captor = ArgumentCaptor.forClass(ProductRecentView.class);
        verify(recentViewRepository).save(captor.capture());
        assertNotNull(captor.getValue().getViewedAt());
    }

    @Test
    void track_unknownProduct_throwsProductNotFoundException() {
        var user = user();
        var productId = UUID.randomUUID();
        when(productRepository.findById(productId)).thenReturn(Optional.empty());

        assertThrows(ProductNotFoundException.class, () -> service.track(user, productId));
    }

    @Test
    void listRecent_returnsMappedProductResponses() {
        var user = user();
        var productId = UUID.randomUUID();
        var product = product(productId);
        var view = ProductRecentView.builder().user(user).product(product).viewedAt(LocalDateTime.now()).build();
        when(recentViewRepository.findRecentByUserId(eq(user.getId()), any(PageRequest.class))).thenReturn(List.of(view));

        var result = service.listRecent(user, 10);

        assertEquals(1, result.size());
        assertEquals("LEITE ITALAC 1L", result.get(0).normalizedName());
    }

    @Test
    void listRecent_empty_returnsEmptyList() {
        var user = user();
        when(recentViewRepository.findRecentByUserId(eq(user.getId()), any(PageRequest.class))).thenReturn(List.of());

        var result = service.listRecent(user, 10);

        assertEquals(List.of(), result);
    }
}
