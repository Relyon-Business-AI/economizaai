package com.relyon.economizai.repository;

import com.relyon.economizai.model.CuratedDictionaryEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CuratedDictionaryEntryRepository extends JpaRepository<CuratedDictionaryEntry, UUID> {

    Optional<CuratedDictionaryEntry> findByKeyword(String keyword);

    long countByOrigin(String origin);

    Page<CuratedDictionaryEntry> findByKeywordContainingIgnoreCase(String keyword, Pageable pageable);
}
