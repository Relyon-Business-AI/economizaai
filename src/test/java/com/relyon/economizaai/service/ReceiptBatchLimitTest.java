package com.relyon.economizaai.service;

import com.relyon.economizaai.exception.ReceiptBatchTooLargeException;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.Collections;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class ReceiptBatchLimitTest {

    private final ReceiptBatchLimit limit = new ReceiptBatchLimit();
    private final LocalizedMessageService messages = new LocalizedMessageService(messageSource());

    private static ResourceBundleMessageSource messageSource() {
        var source = new ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        return source;
    }

    @Test
    void acceptsABatchUpToTheConfiguredSize_andRefusesOneMore() {
        assertThatCode(() -> limit.requireWithinLimit(Collections.nCopies(500, "chave"))).doesNotThrowAnyException();

        var refused = catchThrowableOfType(ReceiptBatchTooLargeException.class,
                () -> limit.requireWithinLimit(Collections.nCopies(501, "chave")));

        assertThat(messages.translate(refused.getMessageKey(), Locale.forLanguageTag("pt"), (Object[]) refused.getArguments()))
                .contains("até 500 notas");
    }

    @Test
    void theLimitAndTheMessage_followTheConfiguredSize() {
        limit.setMaxReceipts(1200);

        assertThatCode(() -> limit.requireWithinLimit(Collections.nCopies(1200, "chave"))).doesNotThrowAnyException();
        var refused = catchThrowableOfType(ReceiptBatchTooLargeException.class,
                () -> limit.requireWithinLimit(Collections.nCopies(1201, "chave")));

        assertThat(messages.translate(refused.getMessageKey(), Locale.forLanguageTag("pt"), (Object[]) refused.getArguments()))
                .contains("até 1200 notas");
        assertThat(messages.translate(refused.getMessageKey(), Locale.ENGLISH, (Object[]) refused.getArguments()))
                .contains("up to 1200 receipts");
    }
}
