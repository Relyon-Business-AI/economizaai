package com.relyon.economizaai.model.enums;

/** Which reference prices a household's purchases are compared with. */
public enum SavingsReference {
    /** What other households paid nearby and, without that, what the household itself usually paid. */
    ALL,
    /** Only what other households paid nearby; a line without it is left out. */
    REGION
}
