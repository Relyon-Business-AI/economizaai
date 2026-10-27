package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.MarketChain;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MarketChainRepository extends JpaRepository<MarketChain, UUID> {

    Optional<MarketChain> findByCnpjRoot(String cnpjRoot);

    @Query(value = """
            SELECT c.id, c.cnpj_root, c.name, c.logo_url, COUNT(m.id) AS market_count
            FROM market_chains c
            LEFT JOIN market_locations m ON m.chain_id = c.id
            WHERE (:state IS NULL OR m.state = :state)
            AND (:search IS NULL OR c.name ILIKE '%' || :search || '%')
            GROUP BY c.id
            ORDER BY c.name
            """,
            countQuery = """
            SELECT COUNT(DISTINCT c.id)
            FROM market_chains c
            LEFT JOIN market_locations m ON m.chain_id = c.id
            WHERE (:state IS NULL OR m.state = :state)
            AND (:search IS NULL OR c.name ILIKE '%' || :search || '%')
            """,
            nativeQuery = true)
    Page<Object[]> findAllFiltered(@Param("state") String state, @Param("search") String search, Pageable pageable);

    @Query(value = "SELECT DISTINCT m.state FROM market_locations m WHERE m.chain_id IS NOT NULL AND m.state IS NOT NULL ORDER BY m.state", nativeQuery = true)
    List<String> findDistinctStatesWithChains();
}
