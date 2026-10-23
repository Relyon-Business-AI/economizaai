package com.relyon.economizaai.model.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReceiptEntryMethodTest {

    @Test
    void scanUrlPayloadIsQrScan() {
        assertEquals(ReceiptEntryMethod.QR_SCAN, ReceiptEntryMethod.derive(ReceiptOrigin.SCAN, false));
    }

    @Test
    void scanBareChaveIsManualChave() {
        // A bare chave = typed or photographed key; server can't tell, defaults to MANUAL_CHAVE.
        assertEquals(ReceiptEntryMethod.MANUAL_CHAVE, ReceiptEntryMethod.derive(ReceiptOrigin.SCAN, true));
    }

    @Test
    void photoOriginIsPhotoReceipt() {
        assertEquals(ReceiptEntryMethod.PHOTO_RECEIPT, ReceiptEntryMethod.derive(ReceiptOrigin.PHOTO, false));
    }

    @Test
    void importOriginIsImport() {
        assertEquals(ReceiptEntryMethod.IMPORT, ReceiptEntryMethod.derive(ReceiptOrigin.IMPORT, true));
    }

    @Test
    void nullOriginIsUnknown() {
        assertEquals(ReceiptEntryMethod.UNKNOWN, ReceiptEntryMethod.derive(null, false));
    }
}
