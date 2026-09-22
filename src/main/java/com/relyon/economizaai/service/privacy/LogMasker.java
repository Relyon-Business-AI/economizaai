package com.relyon.economizaai.service.privacy;

/**
 * Masks PII for log lines. Logs are aggregated, archived, and shown to ops
 * — anything we wouldn't put in a public stack trace stays out.
 *
 * <p>Mask, don't drop: keep enough of the value to correlate (last 4 of a
 * chave, the domain of an email) so two separate log lines about the same
 * subject can still be linked when triaging.
 */
public final class LogMasker {

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
