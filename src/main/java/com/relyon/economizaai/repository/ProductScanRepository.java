package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.ProductScan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProductScanRepository extends JpaRepository<ProductScan, UUID> {
}
