package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.MerchantAccess;

import java.time.LocalDateTime;

/** One chain grant of a MERCHANT user (admin view). */
public record MerchantAccessResponse(String cnpjRoot, LocalDateTime grantedAt) {

    public static MerchantAccessResponse from(MerchantAccess access) {
        return new MerchantAccessResponse(access.getCnpjRoot(), access.getCreatedAt());
    }
}
