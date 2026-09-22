package com.relyon.economizaai.service.sefaz;

final class ScSecurityChallengeDetector {

    private static final String SECURITY_VERIFY_PATH = "sat.sef.sc.gov.br/tax.net/securityverify.aspx";

    private ScSecurityChallengeDetector() {}

    static boolean looksLikeUrl(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        var lower = value.trim().toLowerCase();
        return lower.startsWith("http") && lower.contains(SECURITY_VERIFY_PATH);
    }

    static boolean looksLikeHtml(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        var lower = value.toLowerCase();
        return lower.contains(SECURITY_VERIFY_PATH)
                || lower.contains("cf-turnstile")
                || lower.contains("efetue a validação de segurança")
                || lower.contains("validacao de seguranca")
                || lower.contains("validação de segurança")
                || (lower.contains("cloudflare") && lower.contains("validar"))
                || (lower.contains("cloudflare") && lower.contains("securityverify"));
    }
}
