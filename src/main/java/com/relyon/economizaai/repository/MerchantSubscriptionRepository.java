package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.MerchantSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MerchantSubscriptionRepository extends JpaRepository<MerchantSubscription, UUID> {

    Optional<MerchantSubscription> findByCnpjRoot(String cnpjRoot);

    List<MerchantSubscription> findAllByCnpjRootIn(List<String> cnpjRoots);
}
