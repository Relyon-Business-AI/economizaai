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
     * thread (emails, push notifications) where the recipient's stored locale,
     * not the request's {@code Accept-Language}, decides the language.
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

    /** Maps a stored locale tag ("pt"/"en") to a Locale, defaulting to pt. */
    public static Locale toLocale(String localeTag) {
        if (localeTag == null || localeTag.isBlank()) return DEFAULT_LOCALE;
        return Locale.forLanguageTag(localeTag);
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
