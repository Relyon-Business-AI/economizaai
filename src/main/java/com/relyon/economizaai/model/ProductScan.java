package com.relyon.economizaai.model;

import com.relyon.economizaai.model.enums.ScanMatchType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Log append-only de um escaneamento de código de barras (fluxo admin "Comparar preço").
 * Imutável por design — não estende {@link BaseEntity} (não há {@code updatedAt}).
 */
@Entity
@Table(name = "product_scan")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductScan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(nullable = false, length = 14)
    private String ean;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", nullable = false, length = 20)
    private ScanMatchType matchType;

    @Column(name = "matched_product_id")
    private UUID matchedProductId;

    @Column(name = "generic_name_norm", length = 160)
    private String genericNameNorm;

    @Column(precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(precision = 9, scale = 6)
    private BigDecimal longitude;

    @Column(length = 2)
    private String uf;

    @Column(name = "location_source", length = 20)
    private String locationSource;

    @Column(name = "scanned_at", nullable = false)
    private OffsetDateTime scannedAt;
}
