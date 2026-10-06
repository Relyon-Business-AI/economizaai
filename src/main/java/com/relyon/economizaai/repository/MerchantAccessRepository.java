package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.MerchantAccess;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MerchantAccessRepository extends JpaRepository<MerchantAccess, UUID> {

    List<MerchantAccess> findAllByUserId(UUID userId);

    Optional<MerchantAccess> findByUserIdAndCnpjRoot(UUID userId, String cnpjRoot);

    boolean existsByUserIdAndCnpjRoot(UUID userId, String cnpjRoot);

    void deleteAllByUserId(UUID userId);
}
