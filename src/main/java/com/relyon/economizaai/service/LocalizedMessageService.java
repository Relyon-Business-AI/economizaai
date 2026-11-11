package com.relyon.economizaai.service;

import com.relyon.economizaai.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.util.Locale;

@RequiredArgsConstructor
@Service
public class LocalizedMessageService {

    /** Supported UI languages; anything else falls back to the default (pt). */
    private static final Locale DEFAULT_LOCALE = Locale.forLanguageTag("pt");

    private final MessageSource messageSource;

    public String translate(DomainException exception) {
        var locale = LocaleContextHolder.getLocale();
        return messageSource.getMessage(exception.getMessageKey(), exception.getArguments(), locale);
    }

    public String translate(String key, Object... args) {
        var locale = LocaleContextHolder.getLocale();
        return messageSource.getMessage(key, args, locale);
    }

    /**
     * Translate with an EXPLICIT locale — for messages produced off the request
     * thread (emails, push notifications), where there is no request
     * {@code Accept-Language}; callers pass {@link #toLocale}.
     */
    public String translate(String key, Locale locale, Object... args) {
        return messageSource.getMessage(key, args, locale);
    }

    /**
     * Resolve a Spring {@link MessageSourceResolvable} (e.g. a bean-validation
     * {@code FieldError}) against the message bundle in the request locale, so
     * {@code @NotBlank}/{@code @Size}/… field errors are localized. Falls back
     * to the resolvable's default message when no key matches.
     */
    public String resolve(MessageSourceResolvable resolvable) {
        return messageSource.getMessage(resolvable, LocaleContextHolder.getLocale());
    }

    /**
     * The language of every off-request message (push, e-mail, WhatsApp): always
     * Portuguese. The app has no language selector, so the tag captured from the
     * device at signup is not a user choice and is deliberately ignored here.
     */
    public static Locale toLocale(String localeTag) {
        return DEFAULT_LOCALE;
    }

    /**
     * The current request's language normalized to a SUPPORTED UI tag to persist
     * on the user ("en" when the request asked for English, otherwise "pt").
     * Called at registration so later off-request messages know the language.
     */
    public static String requestLocaleTag() {
        return "en".equals(LocaleContextHolder.getLocale().getLanguage()) ? "en" : "pt";
    }
}
