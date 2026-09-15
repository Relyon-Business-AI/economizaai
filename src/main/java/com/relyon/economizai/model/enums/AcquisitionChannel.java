package com.relyon.economizai.model.enums;

/**
 * Derived, coarse-grained bucket of where a user came from, computed once at
 * signup from the UTM params / click id / referrer the FE forwards. Kept
 * deliberately small so the acquisition dashboard has stable series even as
 * campaign names churn — the raw utm_campaign is still stored for drill-down.
 */
public enum AcquisitionChannel {
    /** Paid Meta traffic (Instagram/Facebook) — fbclid present or utm_source=instagram/facebook with a paid medium. */
    INSTAGRAM_PAID,
    /** Paid Google traffic — gclid present or utm_source=google with a paid medium. */
    GOOGLE_PAID,
    /** Any other paid campaign (utm_medium is cpc/paid/ppc/paid_social) we can't map to a known platform. */
    PAID_OTHER,
    /** Arrived via an external referring site (non-paid, has a referrer we don't own). */
    REFERRAL,
    /** Non-paid but campaign-tagged, or arrived from a search/social link without a paid marker. */
    ORGANIC,
    /** No referrer and no params — typed the URL or opened the app directly. */
    DIRECT,
    /** Signup carried no attribution data at all (older accounts, API signups). */
    UNKNOWN
}
