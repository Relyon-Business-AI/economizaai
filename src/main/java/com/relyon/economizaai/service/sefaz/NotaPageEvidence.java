package com.relyon.economizaai.service.sefaz;

import org.jsoup.Jsoup;

/**
 * Positive proof that a portal response is THIS nota's page: every DANFE prints
 * its own chave de acesso. A response that carries the chave but that the parser
 * could not read is a broken contract (layout changed); one that doesn't is the
 * portal failing to deliver the nota (outage stub, error page) — never a parser
 * regression.
 */
final class NotaPageEvidence {

    private NotaPageEvidence() {}

    /** True when the page's visible text prints the chave (grouping spaces/dots/dashes ignored). */
    static boolean carriesChave(String html, String chaveAcesso) {
        if (html == null || html.isBlank() || chaveAcesso == null || chaveAcesso.isBlank()) {
            return false;
        }
        var visibleDigits = Jsoup.parse(html).text().replaceAll("\\D", "");
        return visibleDigits.contains(chaveAcesso);
    }
}
