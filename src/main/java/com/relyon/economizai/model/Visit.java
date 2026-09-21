package com.relyon.economizai.model;

import com.relyon.economizai.model.enums.AcquisitionChannel;
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

/**
 * An anonymous top-of-funnel "visit" — the web landing fires one beacon per
 * session on first load, carrying the marketing attribution off the URL. Lets us
 * compute our OWN click→signup conversion per campaign, independent of Meta.
 * No PII: {@code anonId} is a random client id, {@code ipHash} is one-way.
 */
@Entity
@Table(name = "visits")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Visit extends BaseEntity {

    @Column(name = "anon_id", nullable = false, length = 64)
    private String anonId;

    @Column(name = "utm_source", length = 120)
    private String utmSource;

    @Column(name = "utm_medium", length = 120)
    private String utmMedium;

    @Column(name = "utm_campaign", length = 200)
    private String utmCampaign;

    @Column(name = "utm_content", length = 200)
    private String utmContent;

    @Column(name = "utm_term", length = 200)
    private String utmTerm;

    @Column(name = "click_id", length = 500)
    private String clickId;

    @Column(length = 500)
    private String referrer;

    @Column(name = "landing_path", length = 500)
    private String landingPath;

    @Enumerated(EnumType.STRING)
    @Column(name = "acquisition_channel", length = 40)
    private AcquisitionChannel acquisitionChannel;

    @Column(length = 20)
    private String platform;

    @Column(name = "ip_hash", length = 64)
    private String ipHash;

    @Column(name = "user_agent", length = 400)
    private String userAgent;
}
