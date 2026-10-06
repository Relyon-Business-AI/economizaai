package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.enums.MerchantSegment;

import java.util.List;

/**
 * The merchant panel's "who am I" view: every chain the logged-in MERCHANT user
 * manages, with the stores of that chain already known to the platform (created
 * lazily from scanned receipts — a chain with no scans yet shows zero stores).
 */
public record MerchantProfileResponse(List<ChainSummary> chains) {

    public record ChainSummary(String cnpjRoot, List<StoreSummary> stores) {}

    public record StoreSummary(
            String cnpj,
            String name,
            String address,
            String city,
            String state,
            MerchantSegment segment,
            long receiptCount) {

        public static StoreSummary from(MarketLocation location, long receiptCount) {
            return new StoreSummary(
                    location.getCnpj(),
                    location.getName(),
                    location.getAddress(),
                    location.getCity(),
                    location.getState(),
                    location.getSegment(),
                    receiptCount);
        }
    }
}
