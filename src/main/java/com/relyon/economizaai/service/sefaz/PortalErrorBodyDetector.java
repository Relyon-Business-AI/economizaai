package com.relyon.economizaai.service.sefaz;

import java.util.regex.Pattern;

/**
 * Some portals answer an outage with HTTP 200 and a bare error stub instead of a
 * 5xx (PR, 2026-11-11: the whole body was "Erro interno no servidor"). That is a
 * transient portal failure — retry it like a 5xx, never hand it to the parser.
 */
final class PortalErrorBodyDetector {

    private static final int STUB_MAX_CHARS = 512;
    private static final Pattern SERVER_ERROR_PHRASE = Pattern.compile(
            "erro interno|internal server error|servi[cç]o indispon[ií]vel|service unavailable"
                    + "|bad gateway|gateway time-?out",
            Pattern.CASE_INSENSITIVE);

    private PortalErrorBodyDetector() {}

    static boolean looksLikeServerErrorStub(String body) {
        if (body == null || body.isBlank() || body.length() > STUB_MAX_CHARS) {
            return false;
        }
        return SERVER_ERROR_PHRASE.matcher(body).find();
    }
}
