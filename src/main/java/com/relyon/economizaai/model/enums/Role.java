package com.relyon.economizaai.model.enums;

public enum Role {
    USER,
    ADMIN,
    /** Conta de mercado (lojista) — acessa /api/v1/merchant/** via merchant_access grants. */
    MERCHANT
}
