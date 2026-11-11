package com.relyon.economizaai.service.privacy;

import java.util.regex.Pattern;

/**
 * Masks PII for log lines. Logs are aggregated, archived, and shown to ops
 * — anything we wouldn't put in a public stack trace stays out.
 *
 * <p>Mask, don't drop: keep enough of the value to correlate (last 4 of a
 * chave, the domain of an email) so two separate log lines about the same
 * subject can still be linked when triaging.
 */
public final class LogMasker {

    private static final Pattern CHAVE_DIGITS = Pattern.compile("\\d{44}");
    private static final Pattern URL_HOST = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://(?:[^/?#@]*@)?([^/?#:]+)");
    private static final String USER_KEY_PREFIX = "user:";

    private LogMasker() {}

    /** "alex@example.com" → "a***@example.com"; null/blank passes through. */
    public static String email(String email) {
        if (email == null || email.isBlank()) return email;
        var at = email.indexOf('@');
        if (at <= 0) return "***";
        var local = email.substring(0, at);
        var domain = email.substring(at);
        if (local.length() <= 1) return local + "***" + domain;
        return local.charAt(0) + "***" + domain;
    }

    /** 44-digit chave de acesso → "****<last 4>". null/short input passes through. */
    public static String chave(String chave) {
        if (chave == null || chave.length() < 8) return chave;
        return "****" + chave.substring(chave.length() - 4);
    }

    /**
     * NFC-e portal / QR URL → "<host> chave=****<last 4>". The full URL carries the 44-digit
     * chave (and signature params), so only the host and the abbreviated chave are kept.
     */
    public static String portalUrl(String url) {
        if (url == null || url.isBlank()) return url;
        var chaveMatcher = CHAVE_DIGITS.matcher(url);
        var maskedChave = chaveMatcher.find() ? chave(chaveMatcher.group()) : "none";
        return hostOf(url) + " chave=" + maskedChave;
    }

    // Regex, not URI.create: real QR URLs carry characters ('|') that URI rejects.
    private static String hostOf(String url) {
        var hostMatcher = URL_HOST.matcher(url.trim());
        return hostMatcher.find() ? hostMatcher.group(1) : "unknown-host";
    }

    /** Rate-limit bucket key ("user:<email>" / "ip:<address>") with the e-mail masked. */
    public static String rateLimitKey(String key) {
        if (key == null || !key.startsWith(USER_KEY_PREFIX)) return key;
        return USER_KEY_PREFIX + email(key.substring(USER_KEY_PREFIX.length()));
    }

    /** Push device tokens vary in length; keep last 4 for correlation, drop the rest. */
    public static String token(String token) {
        if (token == null || token.length() < 8) return token == null ? null : "****";
        return "****" + token.substring(token.length() - 4);
    }

    /** E.164 phone → "****<last 2>"; keep just enough to correlate. null/short passes through. */
    public static String phone(String phone) {
        if (phone == null || phone.length() < 2) return phone;
        return "****" + phone.substring(phone.length() - 2);
    }
}
