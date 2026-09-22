package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.exception.SefazFetchException;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InfosimplesBackedAdapterTest {

    private static final String CE_CHAVE = "23260723301562000170650110000433671111560131";

    private final InfosimplesBackedAdapter adapter = new InfosimplesBackedAdapter("CE");

    @Test
    void claimsConfiguredStates() {
        assertEquals(1, adapter.supportedStates().size());
        assertTrue(adapter.supportedStates().contains(UnidadeFederativa.CE));
    }

    @Test
    void requiresQrSignature_soBareChaveIsRoutedToInfosimples() {
        assertTrue(adapter.requiresQrSignature());
    }

    @Test
    void fetchHtml_throwsSefazFetchToTriggerFallback() {
        // A scanned CE URL reaches fetchHtml, which defers to the Infosimples
        // fallback by throwing the rescuable SefazFetchException.
        var thrown = assertThrows(SefazFetchException.class, () -> adapter.fetchHtml(CE_CHAVE));
        assertEquals("CE", thrown.getArguments()[0]);
    }

    @Test
    void parseHtml_isNeverUsed() {
        assertThrows(UnsupportedOperationException.class,
                () -> adapter.parseHtml("<html/>", CE_CHAVE, null));
    }

    @Test
    void unknownUfInConfigIsIgnored() {
        var adapterWithBogus = new InfosimplesBackedAdapter("CE,ZZ");
        assertEquals(1, adapterWithBogus.supportedStates().size());
        assertTrue(adapterWithBogus.supportedStates().contains(UnidadeFederativa.CE));
    }
}
