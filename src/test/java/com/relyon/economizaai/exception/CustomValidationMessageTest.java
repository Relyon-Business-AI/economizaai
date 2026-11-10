package com.relyon.economizaai.exception;

import com.relyon.economizaai.dto.request.BetaSignupRequest;
import com.relyon.economizaai.dto.request.ImportChavesRequest;
import com.relyon.economizaai.dto.request.UpdatePhoneRequest;
import com.relyon.economizaai.dto.request.VerifyPhoneRequest;
import com.relyon.economizaai.service.LocalizedMessageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.MethodParameter;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.Collections;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Constraints that declare {@code message = "{validation.*}"} must show THAT localized
 * text, not the generic per-constraint one ("não está no formato certo").
 */
class CustomValidationMessageTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(new LocalizedMessageService(messageSource()));

    private static ResourceBundleMessageSource messageSource() {
        var source = new ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        return source;
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    private String fieldErrorFor(Object request, String field, Locale locale) throws NoSuchMethodException {
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        var binding = new BeanPropertyBindingResult(request, request.getClass().getSimpleName());
        validator.validate(request, binding);
        var parameter = new MethodParameter(Object.class.getDeclaredMethod("toString"), -1);
        LocaleContextHolder.setLocale(locale);
        var response = handler.handleValidation(new MethodArgumentNotValidException(parameter, binding));
        return response.getBody().errors().get(field);
    }

    @Test
    void sixDigitCode_usesTheCustomKeyInBothLocales() throws Exception {
        var request = new VerifyPhoneRequest("12ab");

        assertThat(fieldErrorFor(request, "code", Locale.forLanguageTag("pt"))).isEqualTo("o código tem 6 números");
        assertThat(fieldErrorFor(request, "code", Locale.ENGLISH)).isEqualTo("the code has 6 digits");
    }

    @Test
    void phoneFormat_usesTheCustomKey() throws Exception {
        assertThat(fieldErrorFor(new UpdatePhoneRequest("51999"), "phoneNumber", Locale.forLanguageTag("pt")))
                .isEqualTo("use o código do país e o DDD, ex.: +5551999999999");
    }

    @Test
    void importBatchSize_usesTheCustomKey() throws Exception {
        var request = new ImportChavesRequest(Collections.nCopies(501, "1"));

        assertThat(fieldErrorFor(request, "chaves", Locale.forLanguageTag("pt"))).isEqualTo("até 500 notas por vez");
    }

    @Test
    void constraintWithoutCustomMessage_keepsTheGenericLocalizedText() throws Exception {
        assertThat(fieldErrorFor(new BetaSignupRequest("", "jane@economizaai.app", null), "name", Locale.forLanguageTag("pt")))
                .isEqualTo("não pode estar em branco");
    }
}
