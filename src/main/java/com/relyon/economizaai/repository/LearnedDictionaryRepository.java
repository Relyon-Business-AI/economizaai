package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.LearnedDictionaryEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LearnedDictionaryRepository extends JpaRepository<LearnedDictionaryEntry, UUID> {

    Optional<LearnedDictionaryEntry> findByNormalizedToken(String normalizedToken);

    List<LearnedDictionaryEntry> findByNormalizedTokenIn(Collection<String> normalizedTokens);

    Page<LearnedDictionaryEntry> findByNormalizedTokenContainingIgnoreCase(String normalizedToken, Pageable pageable);
}
