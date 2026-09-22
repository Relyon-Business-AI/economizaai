package com.relyon.economizaai.service.sefaz;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromoMarkerDetectorTest {

    private static Element row(String html) {
        return Jsoup.parse("<table><tr>" + html + "</tr></table>").selectFirst("tr");
    }

    @Test
    void detectsPromoFromDescriptionKeyword() {
        assertTrue(PromoMarkerDetector.isPromo(row("<td>...</td>"), "ARROZ TIO J 5KG OFERTA"));
        assertTrue(PromoMarkerDetector.isPromo(row("<td>...</td>"), "PROMOCAO LEITE LONGA VIDA"));
        assertTrue(PromoMarkerDetector.isPromo(row("<td>...</td>"), "Cafe em pó - PROMO!"));
        assertTrue(PromoMarkerDetector.isPromo(row("<td>...</td>"), "DESCONTO especial cesta"));
        assertTrue(PromoMarkerDetector.isPromo(row("<td>...</td>"), "Sabonete COMBO 4un"));
    }

    @Test
    void detectsPromoFromStructuralMarker() {
        var element = row("<td>some item</td><td class=\"qDes\">5,00</td>");
        assertTrue(PromoMarkerDetector.isPromo(element, "ARROZ TIO J 5KG"));
    }

    @Test
    void noFalsePositiveOnRegularItem() {
        assertFalse(PromoMarkerDetector.isPromo(row("<td>some item</td>"), "ARROZ TIO J 5KG"));
        assertFalse(PromoMarkerDetector.isPromo(row("<td>some item</td>"), "LEITE INTEGRAL 1L"));
    }

    @Test
    void emptyDescriptionDoesNotCrash() {
        assertFalse(PromoMarkerDetector.isPromo(row("<td>...</td>"), null));
        assertFalse(PromoMarkerDetector.isPromo(row("<td>...</td>"), ""));
    }
}
