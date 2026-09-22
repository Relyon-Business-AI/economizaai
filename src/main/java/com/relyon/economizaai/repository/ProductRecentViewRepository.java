package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.ProductRecentView;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRecentViewRepository extends JpaRepository<ProductRecentView, UUID> {

    Optional<ProductRecentView> findByUserIdAndProductId(UUID userId, UUID productId);

    @Query("SELECT v FROM ProductRecentView v JOIN FETCH v.product WHERE v.user.id = :userId ORDER BY v.viewedAt DESC")
    List<ProductRecentView> findRecentByUserId(@Param("userId") UUID userId, Pageable pageable);
}
