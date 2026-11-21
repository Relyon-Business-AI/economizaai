package com.relyon.economizaai.service.settings;

/** How a setting's value is typed in, validated and stored (always as canonical text). */
public enum SettingType {
    INTEGER,
    DECIMAL,
    BOOLEAN,
    TEXT,
    /** ISO date (yyyy-MM-dd); blank means "no date". */
    DATE,
    /** Comma-separated whole numbers. */
    INTEGER_LIST,
    /** Comma-separated state codes (RS,PR,SP); blank means "none". */
    UF_LIST,
    /** One of the setting's listed options. */
    OPTION,
    /** Comma-separated subset of the setting's listed options; blank means "none". */
    OPTION_LIST
}
