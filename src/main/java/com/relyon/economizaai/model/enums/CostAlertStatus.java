package com.relyon.economizaai.model.enums;

/** Delivery state of a {@code cost_alert} row. Only {@link #PENDING} rows are retried. */
public enum CostAlertStatus {

    /** Not yet accepted by any provider — the dispatcher keeps retrying. */
    PENDING,

    /** WhatsApp accepted by the provider. */
    DELIVERED,

    /** WhatsApp kept failing (or is not configured); the e-mail fallback was accepted. */
    DELIVERED_BY_EMAIL,

    /** No channel accepted it within the retry horizon — logged as an error. */
    EXPIRED
}
