package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.ShoppingList;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ShoppingListRepository extends JpaRepository<ShoppingList, UUID> {

    List<ShoppingList> findAllByHouseholdId(UUID householdId);

    List<ShoppingList> findAllByOriginHouseholdId(UUID householdId);

    List<ShoppingList> findAllByHouseholdIdOrderByCreatedAtDesc(UUID householdId);
}
