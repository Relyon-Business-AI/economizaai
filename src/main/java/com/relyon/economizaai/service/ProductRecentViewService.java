package com.relyon.economizaai.service;

import com.relyon.economizaai.dto.response.ProductResponse;
import com.relyon.economizaai.exception.ProductNotFoundException;
import com.relyon.economizaai.model.ProductRecentView;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.ProductRecentViewRepository;
import com.relyon.economizaai.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductRecentViewService {

    static final int DEFAULT_LIMIT = 10;

    private final ProductRecentViewRepository recentViewRepository;
    private final ProductRepository productRepository;
    private final HouseholdProductAliasService householdProductAliasService;

    @Transactional
    public void track(User user, UUID productId) {
        var product = productRepository.findById(productId)
                .orElseThrow(ProductNotFoundException::new);
        var view = recentViewRepository.findByUserIdAndProductId(user.getId(), productId)
                .orElseGet(() -> ProductRecentView.builder()
                        .user(user)
                        .product(product)
                        .build());
        view.setViewedAt(LocalDateTime.now());
        recentViewRepository.save(view);
        log.debug("product.view.tracked user={} product={}", user.getId(), productId);
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> listRecent(User user, int limit) {
        var views = recentViewRepository.findRecentByUserId(user.getId(), PageRequest.of(0, limit));
        var friendlyNames = householdProductAliasService.resolvedNamesFor(user.getHousehold().getId(),
                views.stream().map(view -> view.getProduct().getId()).toList());
        return views.stream()
                .map(view -> ProductResponse.from(view.getProduct(), false,
                        friendlyNames.get(view.getProduct().getId())))
                .toList();
    }
}
