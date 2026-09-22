package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.enums.MerchantSegment;
import com.relyon.economizaai.model.enums.MerchantSupportOverride;

import java.time.LocalDateTime;

/**
 * One grey-zone merchant awaiting the admin's supported/blocked verdict.
 * {@code receiptCount} ranks the queue — merchants real users keep scanning
 * are the ones worth investigating first.
 */
public record GreyMerchantResponse(
        String cnpj,
        String name,
        String city,
        String state,
        MerchantSegment segment,
        String cnaeCodes,
        MerchantSupportOverride supportOverride,
        long receiptCount,
        LocalDateTime firstSeenAt) {

    public static GreyMerchantResponse from(MarketLocation market, long receiptCount) {
        return new GreyMerchantResponse(
                market.getCnpj(),
                market.getName(),
                market.getCity(),
                market.getState(),
                market.getSegment(),
                market.getCnaeCodes(),
                market.getSupportOverride(),
                receiptCount,
                market.getCreatedAt());
    }
}
