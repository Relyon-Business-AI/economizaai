package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.service.priceindex.PromoDetector;

import java.time.LocalDate;
import java.util.List;

/**
 * Returned by POST /receipts/{id}/confirm. Carries the updated receipt
 * plus any personal promos detected against the user's own purchase
 * history. Personal promos require ≥3 prior purchases of the same
 * product (configurable via economizaai.personal-promo.*).
 * {@code launchPromo} is filled only when this confirmation granted the
 * launch promo (the user's first confirmed receipt).
 */
public record ConfirmReceiptResponse(
        ReceiptResponse receipt,
        List<PromoDetector.PersonalPromo> personalPromos,
        LaunchPromo launchPromo
) {
    /**
     * The launch promo (free Premium) granted by THIS confirmation — non-null only on the one
     * confirm that granted it, so the app can show the "you got Premium" notice exactly once.
     * {@code validUntil} is the last day of free Premium (Brasília calendar day).
     */
    public record LaunchPromo(LocalDate validUntil) {}

    public ConfirmReceiptResponse(ReceiptResponse receipt, List<PromoDetector.PersonalPromo> personalPromos) {
        this(receipt, personalPromos, null);
    }
}
