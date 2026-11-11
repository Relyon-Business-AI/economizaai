package com.relyon.economizaai.i18n;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The failure keys the ingestion writes to {@code parseErrorReason} (and the retry refusals)
 * must exist in BOTH bundles — a missing key silently falls back to the generic "tente de novo",
 * which is wrong advice for notas that cannot be retried (photo notas, deterministic failures).
 */
class IngestionFailureMessagesTest {

    private static final Locale PT = Locale.forLanguageTag("pt");

    private final ResourceBundleMessageSource messages = messageSource();

    private static ResourceBundleMessageSource messageSource() {
        var source = new ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "receipt.photo.extraction-failed",
            "receipt.retry.refused.chave",
            "receipt.retry.refused.not_found",
            "receipt.retry.refused.not_transmitted",
            "receipt.retry.refused.merchant",
            "receipt.retry.refused.qr",
            "receipt.retry.refused.state",
            "receipt.retry.refused.manual_chave"
    })
    void keyIsTranslatedInPortugueseAndEnglish(String messageKey) {
        var portuguese = messages.getMessage(messageKey, null, PT);
        var english = messages.getMessage(messageKey, null, Locale.ENGLISH);

        assertFalse(portuguese.isBlank());
        assertFalse(english.isBlank());
        assertNotEquals(portuguese, english, "each bundle carries its own text");
    }
}
