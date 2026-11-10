package com.relyon.economizaai.exception;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Machine names (enum statuses, feature flags, document ids) must never reach the
 * user-facing message, and no message may list the supported states.
 */
class UserFacingMessageLeakTest {

    private static final List<Locale> LOCALES = List.of(Locale.forLanguageTag("pt"), Locale.ENGLISH);

    private final ResourceBundleMessageSource messages = messageSource();

    private static ResourceBundleMessageSource messageSource() {
        var source = new ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        return source;
    }

    private String render(DomainException exception, Locale locale) {
        return messages.getMessage(exception.getMessageKey(), exception.getArguments(), locale);
    }

    @Test
    void receiptNotEditable_neverShowsTheStatusName() {
        var exception = new ReceiptNotEditableException("CONFIRMED");

        assertThat(exception.getArguments()).isEmpty();
        assertThat(exception.getCurrentStatus()).isEqualTo("CONFIRMED");
        LOCALES.forEach(locale -> assertThat(render(exception, locale)).doesNotContain("CONFIRMED"));
    }

    @Test
    void paywall_neverShowsTheFeatureEnumName() {
        var exception = new PaywallException("RECEIPT_UPLOAD_UNLIMITED");

        assertThat(exception.getArguments()).isEmpty();
        assertThat(exception.getFeatureName()).isEqualTo("RECEIPT_UPLOAD_UNLIMITED");
        LOCALES.forEach(locale -> assertThat(render(exception, locale))
                .doesNotContain("RECEIPT_UPLOAD_UNLIMITED")
                .doesNotContain("PRO"));
    }

    @Test
    void legalVersion_neverShowsDocumentIdOrVersions() {
        var exception = new InvalidLegalVersionException("privacy-policy", "2025-01-01", "2026-11-01");

        assertThat(exception.getArguments()).isEmpty();
        LOCALES.forEach(locale -> assertThat(render(exception, locale))
                .doesNotContain("privacy-policy")
                .doesNotContain("2025-01-01")
                .doesNotContain("2026-11-01"));
    }

    @Test
    void unsupportedState_namesOnlyTheStateAndNeverListsSupportedOnes() {
        var exception = new UnsupportedStateException("AC");

        LOCALES.forEach(locale -> assertThat(render(exception, locale))
                .contains("AC")
                .doesNotContain("RS")
                .doesNotContain("SC")
                .doesNotContain("SP"));
    }
}
