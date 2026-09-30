package com.relyon.economizaai.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ReceiptAlreadyIngestedExceptionTest {

    @Test
    void messageArgumentCarriesMaskedChaveNeverTheFullOne() {
        var fullChave = "43241093015006000103651200002591741234567890";

        var exception = new ReceiptAlreadyIngestedException(fullChave);

        assertEquals("receipt.already.ingested", exception.getMessageKey());
        assertArrayEquals(new String[]{"****7890"}, exception.getArguments());
    }
}
