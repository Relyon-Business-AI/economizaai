package com.relyon.economizaai.exception;

import com.relyon.economizaai.service.LocalizedMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private static final String VALIDATION_FAILED_KEY = "validation.failed";

    private final LocalizedMessageService messageService;

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleEmailAlreadyExists(EmailAlreadyExistsException ex) {
        return respond(ex, HttpStatus.CONFLICT, "Registration attempt with existing email");
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException ex) {
        return respond(ex, HttpStatus.UNAUTHORIZED, "Failed login attempt");
    }

    /**
     * Password login attempted on a social account. 409 (distinct from the 401
     * for a wrong password so the FE branches cleanly) + the provider in the
     * errors map so the app can highlight the right "Continue with …" button.
     */
    @ExceptionHandler(SocialAccountLoginException.class)
    public ResponseEntity<ErrorResponse> handleSocialAccountLogin(SocialAccountLoginException ex) {
        var message = messageService.translate(ex);
        log.warn("Password login attempted on {} social account", ex.getProvider());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(HttpStatus.CONFLICT.value(), message, LocalDateTime.now(),
                        Map.of("provider", ex.getProvider().name())));
    }

    @ExceptionHandler(InvalidOAuthTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidOAuthToken(InvalidOAuthTokenException ex) {
        return respond(ex, HttpStatus.UNAUTHORIZED, "Invalid social-login token");
    }

    @ExceptionHandler(InvalidWebhookSecretException.class)
    public ResponseEntity<ErrorResponse> handleInvalidWebhookSecret(InvalidWebhookSecretException ex) {
        return respond(ex, HttpStatus.UNAUTHORIZED, "Invalid webhook secret");
    }

    @ExceptionHandler(InvalidCurrentPasswordException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCurrentPassword(InvalidCurrentPasswordException ex) {
        return respond(ex, HttpStatus.BAD_REQUEST, "Invalid current password");
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFound(UserNotFoundException ex) {
        return respond(ex, HttpStatus.NOT_FOUND, "User not found");
    }

    @ExceptionHandler({HouseholdNotFoundException.class, ReceiptNotFoundException.class, ReceiptItemNotFoundException.class, ProductNotFoundException.class, MarketNotFoundException.class, NotInHouseholdException.class, NotificationNotFoundException.class, ShoppingListNotFoundException.class, PriceAlertNotFoundException.class, NotificationRuleNotFoundException.class, CustomCategoryNotFoundException.class, EcommerceOfferNotFoundException.class, GarimpoMarketplaceNotFoundException.class, GarimpoWatchNotFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(DomainException ex) {
        return respond(ex, HttpStatus.NOT_FOUND, "Entity not found");
    }

    @ExceptionHandler({InvalidInviteCodeException.class, InvalidQrPayloadException.class, UnsupportedStateException.class, ManualChaveUnsupportedException.class, ReceiptParseException.class, ReceiptNotEditableException.class, AlreadyInHouseholdException.class, InvalidLegalVersionException.class, InvalidProfilePictureException.class, InvalidAuthTokenException.class, InvalidShoppingListItemException.class, InvalidProductMergeException.class, InvalidCategoryMigrationException.class, InvalidNotificationRuleException.class, InvalidCnpjException.class, InvalidPhoneNumberException.class, InvalidPhoneVerificationException.class, InvalidNotificationEventException.class, InvalidConsentRequestException.class, InvalidReceiptPhotoException.class, AdminUserDeletionException.class, InvalidExportFormatException.class, InvalidItemPriceException.class, InvalidGarimpoWatchException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(DomainException ex) {
        return respond(ex, HttpStatus.BAD_REQUEST, "Bad request");
    }

    @ExceptionHandler({ReceiptAlreadyIngestedException.class, ProductAliasConflictException.class, EanConflictException.class, InvalidProductDeletionException.class})
    public ResponseEntity<ErrorResponse> handleConflict(DomainException ex) {
        return respond(ex, HttpStatus.CONFLICT, "Conflict");
    }

    @ExceptionHandler(SefazFetchException.class)
    public ResponseEntity<ErrorResponse> handleSefazFetch(SefazFetchException ex) {
        return respond(ex, HttpStatus.BAD_GATEWAY, "SEFAZ fetch failed");
    }

    @ExceptionHandler(CaptchaSolveFailedException.class)
    public ResponseEntity<ErrorResponse> handleCaptchaSolveFailed(CaptchaSolveFailedException ex) {
        return respond(ex, HttpStatus.BAD_GATEWAY, "Captcha solve failed");
    }

    @ExceptionHandler(CaptchaUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleCaptchaUnavailable(CaptchaUnavailableException ex) {
        return respond(ex, HttpStatus.SERVICE_UNAVAILABLE, "Captcha-gated state, no solver configured");
    }

    @ExceptionHandler(GarimpoProviderNotConfiguredException.class)
    public ResponseEntity<ErrorResponse> handleGarimpoProviderNotConfigured(GarimpoProviderNotConfiguredException ex) {
        return respond(ex, HttpStatus.SERVICE_UNAVAILABLE, "Garimpo marketplace not configured");
    }

    @ExceptionHandler(GarimpoSearchFailedException.class)
    public ResponseEntity<ErrorResponse> handleGarimpoSearchFailed(GarimpoSearchFailedException ex) {
        return respond(ex, HttpStatus.BAD_GATEWAY, "Garimpo marketplace search failed");
    }

    @ExceptionHandler(ReportEmailUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleReportEmailUnavailable(ReportEmailUnavailableException ex) {
        return respond(ex, HttpStatus.SERVICE_UNAVAILABLE, "Report e-mail delivery unavailable");
    }

    @ExceptionHandler(PhotoExtractionUnavailableException.class)
    public ResponseEntity<ErrorResponse> handlePhotoExtractionUnavailable(PhotoExtractionUnavailableException ex) {
        return respond(ex, HttpStatus.SERVICE_UNAVAILABLE, "Photo extraction unavailable");
    }

    @ExceptionHandler(OcrUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleOcrUnavailable(OcrUnavailableException ex) {
        return respond(ex, HttpStatus.SERVICE_UNAVAILABLE, "OCR engine not available on this host");
    }

    @ExceptionHandler(PaywallException.class)
    public ResponseEntity<ErrorResponse> handlePaywall(PaywallException ex) {
        return respond(ex, HttpStatus.PAYMENT_REQUIRED, "Paywall: PRO feature blocked for FREE tier");
    }

    @ExceptionHandler(PaidApiQuotaExceededException.class)
    public ResponseEntity<ErrorResponse> handlePaidApiQuota(PaidApiQuotaExceededException ex) {
        return respond(ex, HttpStatus.TOO_MANY_REQUESTS, "Paid-API daily cap reached for user");
    }

    @ExceptionHandler(PaidApiUnavailableException.class)
    public ResponseEntity<ErrorResponse> handlePaidApiUnavailable(PaidApiUnavailableException ex) {
        return respond(ex, HttpStatus.SERVICE_UNAVAILABLE, "Paid-API circuit breaker open");
    }

    @ExceptionHandler(PaidApiBudgetExceededException.class)
    public ResponseEntity<ErrorResponse> handlePaidApiBudget(PaidApiBudgetExceededException ex) {
        return respond(ex, HttpStatus.SERVICE_UNAVAILABLE, "Paid-API global daily budget exhausted");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        var errors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(FieldError::getField, this::localizedFieldError, (first, second) -> first));
        var message = messageService.translate(VALIDATION_FAILED_KEY);
        log.warn("Validation failed: {}", errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(HttpStatus.BAD_REQUEST.value(), message, LocalDateTime.now(), errors));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        var message = messageService.translate(VALIDATION_FAILED_KEY);
        log.warn("Type mismatch for parameter '{}': {}", ex.getName(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(HttpStatus.BAD_REQUEST.value(), message, LocalDateTime.now()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException ex) {
        var message = messageService.translate(VALIDATION_FAILED_KEY);
        log.warn("Malformed request body: {}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(HttpStatus.BAD_REQUEST.value(), message, LocalDateTime.now()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException ex) {
        var message = messageService.translate("error.notfound");
        log.warn("No handler for request: {}", ex.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(HttpStatus.NOT_FOUND.value(), message, LocalDateTime.now()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        var message = messageService.translate("error.method.not.allowed");
        log.warn("Method not allowed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(new ErrorResponse(HttpStatus.METHOD_NOT_ALLOWED.value(), message, LocalDateTime.now()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        var message = messageService.translate("error.internal");
        log.error("Unexpected error: {}: {}", ex.getClass().getName(), ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), message, LocalDateTime.now()));
    }

    /** Localize a field error via the message bundle; never null (falls back to the raw default). */
    private String localizedFieldError(FieldError fieldError) {
        var localized = messageService.resolve(fieldError);
        if (localized != null) return localized;
        return fieldError.getDefaultMessage() != null ? fieldError.getDefaultMessage() : "invalid";
    }

    private ResponseEntity<ErrorResponse> respond(DomainException ex, HttpStatus status, String logContext) {
        var message = messageService.translate(ex);
        log.warn("{}: {}", logContext, message);
        return ResponseEntity.status(status)
                .body(new ErrorResponse(status.value(), message, LocalDateTime.now()));
    }

    public record ErrorResponse(int status, String message, LocalDateTime timestamp, Map<String, String> errors) {
        public ErrorResponse(int status, String message, LocalDateTime timestamp) {
            this(status, message, timestamp, null);
        }
    }
}
