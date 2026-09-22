package com.relyon.economizaai.i18n;

import com.relyon.economizaai.dto.request.BetaSignupRequest;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Locale;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks that bean-validation FIELD errors are localized through our message
 * bundle — exercising the exact path the exception handler uses (default
 * validator produces FieldErrors, MessageSource resolves them by code in the
 * request locale). Also pins the @Size positional-arg order so the "between
 * min and max" template stays correct.
 */
class I18nValidationMessagesTest {

    private static final Locale PT = Locale.forLanguageTag("pt");
    private static final Locale EN = Locale.ENGLISH;

    private final ResourceBundleMessageSource messages = messageSource();

    private ResourceBundleMessageSource messageSource() {
        var source = new ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        return source;
    }

    private BeanPropertyBindingResult validate(Object dto) {
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        var binding = new BeanPropertyBindingResult(dto, dto.getClass().getSimpleName());
        validator.validate(dto, binding);
        return binding;
    }

    private String resolve(BeanPropertyBindingResult binding, String field, Locale locale) {
        var fieldError = binding.getFieldErrors().stream()
                .filter(error -> error.getField().equals(field))
                .findFirst().orElseThrow(() -> new AssertionError("no error on " + field));
        return messages.getMessage(fieldError, locale);
    }

    @Test
    void notBlankAndEmail_localizedPtVsEn() {
        // name blank (@NotBlank), email malformed (@Email)
        var binding = validate(new BetaSignupRequest("", "not-an-email", null));

        assertEquals("não pode estar em branco", resolve(binding, "name", PT));
        assertEquals("must not be blank", resolve(binding, "name", EN));
        assertEquals("deve ser um e-mail válido", resolve(binding, "email", PT));
        assertEquals("must be a well-formed email address", resolve(binding, "email", EN));
    }

    @Test
    void sizeError_showsBoundsInRightOrder_bothLocales() {
        // phone is @Size(max = 30): 31 chars violates it. Pins arg order [field, max, min].
        var binding = validate(new BetaSignupRequest("Jane", "jane@test.com", "1".repeat(31)));

        assertEquals("tamanho deve estar entre 0 e 30", resolve(binding, "phone", PT));
        assertEquals("size must be between 0 and 30", resolve(binding, "phone", EN));
    }

    @Test
    void everyConstraintKeyResolvesInBothLocales() {
        // No raw {code} leaks: each key we added must exist in both bundles.
        for (var key : java.util.List.of("NotBlank", "NotEmpty", "NotNull", "Email",
                "Size", "Min", "Max", "DecimalMin", "DecimalMax", "Pattern")) {
            var pt = messages.getMessage(key, new Object[]{"", 0, 0}, PT);
            var en = messages.getMessage(key, new Object[]{"", 0, 0}, EN);
            assertTrue(!pt.isBlank() && !pt.equals(key), "pt missing for " + key);
            assertTrue(!en.isBlank() && !en.equals(key), "en missing for " + key);
        }
    }
}
