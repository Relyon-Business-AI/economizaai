package com.relyon.economizaai.service;

import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.enums.PhotoReceiptField;
import com.relyon.economizaai.model.enums.ReceiptOrigin;
import com.relyon.economizaai.model.enums.ReceiptStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * A photo nota is unverified: whatever the model couldn't read (date, market, a name, a price)
 * the user must fill by hand before confirming, so it lands in history like any SEFAZ nota.
 * Excluded items don't count — they're out of totals and the index.
 */
public final class PhotoReceiptCompleteness {

    private PhotoReceiptCompleteness() {}

    /** Missing fields for a photo nota under review; {@code null} when the rule doesn't apply. */
    public static List<PhotoReceiptField> missingFieldsForReview(Receipt receipt) {
        if (receipt.getOrigin() != ReceiptOrigin.PHOTO || receipt.getStatus() != ReceiptStatus.PENDING_CONFIRMATION) {
            return null;
        }
        return missingFields(receipt);
    }

    public static List<PhotoReceiptField> missingFields(Receipt receipt) {
        var missing = new ArrayList<PhotoReceiptField>();
        if (receipt.getIssuedAt() == null) {
            missing.add(PhotoReceiptField.ISSUED_AT);
        }
        if (isBlank(receipt.getMarketName())) {
            missing.add(PhotoReceiptField.MARKET);
        }
        if (countUnnamedItems(receipt) > 0) {
            missing.add(PhotoReceiptField.ITEM_NAMES);
        }
        if (countUnpricedItems(receipt) > 0) {
            missing.add(PhotoReceiptField.ITEM_PRICES);
        }
        return missing;
    }

    public static long countUnnamedItems(Receipt receipt) {
        return activeItems(receipt).stream()
                .filter(item -> isBlank(item.getRawDescription()) && isBlank(item.getFriendlyDescription()))
                .count();
    }

    public static long countUnpricedItems(Receipt receipt) {
        return activeItems(receipt).stream()
                .filter(item -> item.getTotalPrice() == null)
                .count();
    }

    private static List<ReceiptItem> activeItems(Receipt receipt) {
        return receipt.getItems().stream().filter(item -> !item.isExcluded()).toList();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
