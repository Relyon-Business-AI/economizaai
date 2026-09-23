package com.relyon.economizaai.model.enums;

/**
 * Whether the purchase happened in a physical store or remotely (online). Derived from the
 * fiscal document's {@code indPres} (indicador de presença do comprador): presencial (1/5) →
 * IN_STORE; não presencial (2 internet, 3 teleatendimento, 4 entrega domicílio, 9 outros) →
 * ONLINE. NFC-e (model 65) is always presencial → IN_STORE, even when {@code indPres} isn't
 * rendered. Orthogonal to {@link MerchantSegment} (a supermarket delivery is ONLINE + grocery).
 */
public enum ReceiptChannel {
    IN_STORE,
    ONLINE
}
