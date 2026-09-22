package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.ManualPurchase;
import com.relyon.economizaai.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ManualPurchaseRepository extends JpaRepository<ManualPurchase, UUID> {

    List<ManualPurchase> findAllByHouseholdId(UUID householdId);

    List<ManualPurchase> findAllByOriginHouseholdId(UUID householdId);

    long countByProduct(Product product);

    @Modifying
    @Query("UPDATE ManualPurchase m SET m.product = :survivor WHERE m.product = :absorbed")
    int repointProduct(@Param("absorbed") Product absorbed, @Param("survivor") Product survivor);
}
