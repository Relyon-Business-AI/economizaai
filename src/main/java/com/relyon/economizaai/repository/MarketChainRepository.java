package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.MarketChain;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MarketChainRepository extends JpaRepository<MarketChain, UUID> {

    Optional<MarketChain> findByCnpjRoot(String cnpjRoot);
}
