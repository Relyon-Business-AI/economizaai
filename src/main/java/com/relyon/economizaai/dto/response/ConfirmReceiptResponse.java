package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.service.priceindex.PromoDetector;

import java.util.List;

/**
 * Returned by POST /receipts/{id}/confirm. Carries the updated receipt
 * plus any personal promos detected against the user's own purchase
 * history. Personal promos require ≥3 prior purchases of the same
 * product (configurable via economizaai.personal-promo.*).
 */
public record ConfirmReceiptResponse(
        ReceiptResponse receipt,
        List<PromoDetector.PersonalPromo> personalPromos
) {}
