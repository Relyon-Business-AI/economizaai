package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.SpendingReportDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface SpendingReportDeliveryRepository extends JpaRepository<SpendingReportDelivery, UUID> {
}
