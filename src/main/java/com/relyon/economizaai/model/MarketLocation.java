package com.relyon.economizaai.model;

import com.relyon.economizaai.model.enums.MerchantSegment;
import com.relyon.economizaai.model.enums.MerchantSupportOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Cached geolocation for a market identified by CNPJ. Populated lazily
 * (Nominatim / OSM) so receipt-confirm never blocks on the geocoder.
 *
 * Each Brazilian market unit has its own 14-digit CNPJ; the first 8
 * digits ("cnpj_root") identify the chain (Zaffari, Carrefour, etc.).
 */
@Entity
@Table(name = "market_locations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class MarketLocation extends BaseEntity {

    @Column(nullable = false, unique = true, length = 14)
    private String cnpj;

    @Column(name = "cnpj_root", nullable = false, length = 8)
    private String cnpjRoot;

    @Column(length = 255)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String address;

    @Column(precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name = "geocoded_at")
    private LocalDateTime geocodedAt;

    @Column(name = "geocode_failed_at")
    private LocalDateTime geocodeFailedAt;

    @Column(name = "geocode_attempts", nullable = false)
    @lombok.Builder.Default
    private int geocodeAttempts = 0;

    @Column(length = 120)
    private String city;

    @Column(length = 2)
    private String state;

    /** Official IBGE municipality code (7 digits), from the BrasilAPI CNPJ lookup. */
    @Column(name = "ibge_city_code", length = 7)
    private String ibgeCityCode;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    @lombok.Builder.Default
    private MerchantSegment segment = MerchantSegment.UNKNOWN;

    @Column(name = "segment_classified_at")
    private LocalDateTime segmentClassifiedAt;

    @Column(name = "segment_attempts", nullable = false)
    @lombok.Builder.Default
    private int segmentAttempts = 0;

    /** Raw CNAE codes (comma-separated) from the registry lookup — kept so grey-zone merchants can be reviewed without re-querying. */
    @Column(name = "cnae_codes", columnDefinition = "TEXT")
    private String cnaeCodes;

    /** Admin verdict on a grey-zone merchant; null means "go by segment". */
    @Enumerated(EnumType.STRING)
    @Column(name = "support_override", length = 20)
    private MerchantSupportOverride supportOverride;

    /** When the admin was emailed about this grey-zone merchant (dedup — one alert per CNPJ, ever). */
    @Column(name = "gray_sighting_notified_at")
    private LocalDateTime graySightingNotifiedAt;

    public boolean hasCoordinates() {
        return latitude != null && longitude != null;
    }
}
