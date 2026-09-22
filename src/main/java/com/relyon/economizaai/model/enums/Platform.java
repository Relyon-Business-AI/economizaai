package com.relyon.economizaai.model.enums;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Client platform a user authenticated from. Sent (optionally) by the FE on every
 * login/register call so we can track the registration platform and the last
 * login time per platform.
 */
public enum Platform {
    WEB, ANDROID, IOS;

    // Lenient, best-effort binding: the platform is optional client metadata, so an
    // unknown or oddly-cased value is ignored (null) rather than 400-ing the whole
    // auth request. New clients send the exact upper-case name.
    @JsonCreator
    public static Platform fromValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Platform.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
