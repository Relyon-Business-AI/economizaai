package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CategoryRepository extends JpaRepository<Category, UUID> {

    Optional<Category> findBySlug(String slug);

    List<Category> findAllByActiveTrueOrderBySortOrderAsc();

    /** True when a built-in taxonomy node (category or subcategory) uses this name in either locale. */
    boolean existsByNamePtIgnoreCaseOrNameEnIgnoreCase(String namePt, String nameEn);
}
