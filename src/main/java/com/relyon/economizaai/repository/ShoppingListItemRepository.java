package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.ShoppingListItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface ShoppingListItemRepository extends JpaRepository<ShoppingListItem, UUID> {

    long countByProduct(Product product);

    @Modifying
    @Query("UPDATE ShoppingListItem s SET s.product = :survivor WHERE s.product = :absorbed")
    int repointProduct(@Param("absorbed") Product absorbed, @Param("survivor") Product survivor);
}
