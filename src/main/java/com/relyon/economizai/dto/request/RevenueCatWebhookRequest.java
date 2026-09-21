package com.relyon.economizai.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * RevenueCat webhook payload. RevenueCat wraps the meaningful data in an
 * {@code event} object; we read only the fields we need and ignore the rest
 * (the schema is large and versioned). See
 * https://www.revenuecat.com/docs/integrations/webhooks/event-types-and-fields
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RevenueCatWebhookRequest(Event event) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Event(
            String type,
            /** The id the app set when configuring RevenueCat — we use our user UUID (or email). */
            @JsonProperty("app_user_id") String appUserId,
            /** End of the paid period, epoch millis. Null for some non-renewing/grant events. */
            @JsonProperty("expiration_at_ms") Long expirationAtMs,
            @JsonProperty("product_id") String productId,
            /** Actual price paid in the store currency (RevenueCat sends this on purchase/renewal events). */
            @JsonProperty("price") Double price,
            @JsonProperty("currency") String currency,
            /** When the purchase happened, epoch millis. Falls back to the event's own timestamp. */
            @JsonProperty("purchased_at_ms") Long purchasedAtMs,
            String id
    ) {}
}
