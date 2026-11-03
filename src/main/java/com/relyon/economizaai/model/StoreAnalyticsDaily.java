package com.relyon.economizaai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Getter
@Setter
@Entity
@Table(name = "store_analytics_daily")
public class StoreAnalyticsDaily extends BaseEntity {

    @Column(nullable = false, length = 10)
    private String platform;    // 'IOS' | 'ANDROID'

    @Column(name = "report_date", nullable = false)
    private LocalDate reportDate;

    @Column(name = "source_type", nullable = false, length = 80)
    private String sourceType;  // e.g. 'APP_STORE_SEARCH', 'APP_STORE_BROWSE', 'TOTAL'

    @Column(nullable = false)
    private long impressions = 0;

    @Column(name = "page_views", nullable = false)
    private long pageViews = 0;

    @Column(name = "app_units", nullable = false)
    private long appUnits = 0;  // first-time downloads / installs

    @Column(name = "synced_at", nullable = false)
    private OffsetDateTime syncedAt = OffsetDateTime.now();
}
