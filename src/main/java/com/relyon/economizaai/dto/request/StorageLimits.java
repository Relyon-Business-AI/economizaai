package com.relyon.economizaai.dto.request;

/**
 * Largest values the NUMERIC columns behind the request DTOs can hold. A number above them is
 * refused at validation (400) instead of failing when the row is written (500).
 */
final class StorageLimits {

    /** Money columns: NUMERIC(12,2). */
    static final String MONEY = "9999999999.99";

    /** Quantity columns: NUMERIC(12,3). */
    static final String QUANTITY = "999999999.999";

    private StorageLimits() {}
}
