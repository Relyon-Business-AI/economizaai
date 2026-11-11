package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.request.ChangePasswordRequest;
import com.relyon.economizaai.dto.request.ConfirmEmailChangeRequest;
import com.relyon.economizaai.dto.request.DeleteAccountRequest;
import com.relyon.economizaai.dto.request.RequestEmailChangeRequest;
import com.relyon.economizaai.dto.request.SetPasswordRequest;
import com.relyon.economizaai.dto.request.UpdateContributionRequest;
import com.relyon.economizaai.dto.request.UpdateDigestPreferencesRequest;
import com.relyon.economizaai.dto.request.UpdateHomeLocationRequest;
import com.relyon.economizaai.dto.request.UpdateNotificationPreferencesRequest;
import com.relyon.economizaai.dto.request.UpdatePhoneRequest;
import com.relyon.economizaai.dto.request.UpdatePushTokenRequest;
import com.relyon.economizaai.dto.request.UpdateUserRequest;
import com.relyon.economizaai.dto.request.UpdateWhatsAppRequest;
import com.relyon.economizaai.dto.request.VerifyPhoneRequest;
import com.relyon.economizaai.dto.response.CepLocationResponse;
import com.relyon.economizaai.dto.response.DigestPreferencesResponse;
import com.relyon.economizaai.dto.response.NotificationPreferenceResponse;
import com.relyon.economizaai.dto.response.SavingsSummaryResponse;
import com.relyon.economizaai.dto.response.StoreReviewPromptResponse;
import com.relyon.economizaai.dto.response.SubscriptionStatusResponse;
import com.relyon.economizaai.dto.response.UserDataExportResponse;
import com.relyon.economizaai.dto.response.UserResponse;
import com.relyon.economizaai.dto.response.WhatsAppStatusResponse;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.exception.CepNotResolvableException;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.UserService;
import com.relyon.economizaai.service.geo.CepLocationService;
import com.relyon.economizaai.service.auth.EmailChangeService;
import com.relyon.economizaai.service.auth.EmailVerificationService;
import com.relyon.economizaai.service.auth.PhoneVerificationService;
import com.relyon.economizaai.service.notifications.NotificationPreferenceService;
import com.relyon.economizaai.service.notifications.SavingsService;
import com.relyon.economizaai.service.notifications.WhatsAppOptInService;
import com.relyon.economizaai.service.profile.ProfilePictureService;
import com.relyon.economizaai.service.profile.StoreReviewPromptService;
import com.relyon.economizaai.service.subscription.SubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "Users", description = "Authenticated user profile management and LGPD rights")
public class UserController {

    private final UserService userService;
    private final LocalizedMessageService messageService;
    private final NotificationPreferenceService notificationPreferenceService;
    private final ProfilePictureService profilePictureService;
    private final EmailVerificationService emailVerificationService;
    private final EmailChangeService emailChangeService;
    private final PhoneVerificationService phoneVerificationService;
    private final SavingsService savingsService;
    private final SubscriptionService subscriptionService;
    private final WhatsAppOptInService whatsAppOptInService;
    private final StoreReviewPromptService storeReviewPromptService;
    private final CepLocationService cepLocationService;

    @GetMapping("/me")
    public ResponseEntity<UserResponse> getProfile(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(userService.getProfile(user));
    }

    @PutMapping("/me")
    public ResponseEntity<UserResponse> updateProfile(@AuthenticationPrincipal User user,
                                                      @Valid @RequestBody UpdateUserRequest request) {
        return ResponseEntity.ok(userService.updateProfile(user, request));
    }

    @PutMapping("/me/password")
    public ResponseEntity<Map<String, String>> changePassword(@AuthenticationPrincipal User user,
                                                              @Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(user, request);
        return ResponseEntity.ok(Map.of("message", messageService.translate("user.password.changed")));
    }

    @PostMapping("/me/password")
    public ResponseEntity<Map<String, String>> setPassword(@AuthenticationPrincipal User user,
                                                           @Valid @RequestBody SetPasswordRequest request) {
        userService.setPassword(user, request);
        return ResponseEntity.ok(Map.of("message", messageService.translate("user.password.set")));
    }

    @PatchMapping("/me/contribution")
    public ResponseEntity<UserResponse> updateContribution(@AuthenticationPrincipal User user,
                                                           @Valid @RequestBody UpdateContributionRequest request) {
        return ResponseEntity.ok(userService.updateContribution(user, request));
    }

    @PatchMapping("/me/location")
    public ResponseEntity<UserResponse> updateHomeLocation(@AuthenticationPrincipal User user,
                                                           @Valid @RequestBody UpdateHomeLocationRequest request) {
        return ResponseEntity.ok(userService.updateHomeLocation(user, request));
    }

    /**
     * Resolve um CEP para coordenada aproximada (alternativa ao GPS, funciona na web).
     * Só pré-visualiza — o salvamento continua em {@code PATCH /me/location}. 404 quando
     * o CEP não resolve.
     */
    @GetMapping("/me/location/cep")
    public ResponseEntity<CepLocationResponse> resolveCep(@RequestParam String cep) {
        var resolved = cepLocationService.resolve(cep).orElseThrow(CepNotResolvableException::new);
        return ResponseEntity.ok(new CepLocationResponse(resolved.latitude(), resolved.longitude(), resolved.label()));
    }

    @PatchMapping("/me/push-token")
    public ResponseEntity<Map<String, String>> updatePushToken(@AuthenticationPrincipal User user,
                                                               @Valid @RequestBody UpdatePushTokenRequest request) {
        notificationPreferenceService.updatePushToken(user, request);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping("/me/store-review-prompt")
    public ResponseEntity<StoreReviewPromptResponse> storeReviewPrompt(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(storeReviewPromptService.evaluate(user));
    }

    @PostMapping("/me/store-review-prompt")
    public ResponseEntity<Map<String, String>> storeReviewPrompted(@AuthenticationPrincipal User user) {
        storeReviewPromptService.recordPrompted(user);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping("/me/notification-preferences")
    public ResponseEntity<List<NotificationPreferenceResponse>> notificationPreferences(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(notificationPreferenceService.list(user));
    }

    @PutMapping("/me/notification-preferences")
    public ResponseEntity<List<NotificationPreferenceResponse>> updateNotificationPreferences(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody UpdateNotificationPreferencesRequest request) {
        return ResponseEntity.ok(notificationPreferenceService.update(user, request));
    }

    @GetMapping("/me/digest-preferences")
    public ResponseEntity<DigestPreferencesResponse> digestPreferences(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(notificationPreferenceService.getDigestPreferences(user));
    }

    @PutMapping("/me/digest-preferences")
    public ResponseEntity<DigestPreferencesResponse> updateDigestPreferences(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody UpdateDigestPreferencesRequest request) {
        return ResponseEntity.ok(notificationPreferenceService.updateDigestPreferences(user, request));
    }

    @GetMapping("/me/savings")
    public ResponseEntity<SavingsSummaryResponse> savings(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(savingsService.summarize(user));
    }

    @GetMapping("/me/subscription")
    public ResponseEntity<SubscriptionStatusResponse> subscription(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(subscriptionService.statusFor(user));
    }

    @GetMapping("/me/export")
    public ResponseEntity<UserDataExportResponse> exportData(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(userService.exportData(user));
    }

    @DeleteMapping("/me")
    public ResponseEntity<Map<String, String>> deleteAccount(@AuthenticationPrincipal User user,
                                                             @Valid @RequestBody(required = false) DeleteAccountRequest request) {
        userService.deleteAccount(user, request);
        return ResponseEntity.ok(Map.of("message", messageService.translate("user.account.deleted")));
    }

    @PostMapping(value = "/me/profile-picture", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> uploadProfilePicture(@AuthenticationPrincipal User user,
                                                                    @RequestParam("file") MultipartFile file) {
        profilePictureService.upload(user, file);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * The caller's picture. Strong {@code ETag} + {@code If-None-Match} → 304, so the app
     * revalidates its on-device copy for free instead of re-downloading on every screen.
     * X-Profile-Picture-Fallback lets the FE distinguish a generated initials avatar from
     * a user-uploaded photo without a separate metadata round-trip.
     */
    @GetMapping("/me/profile-picture")
    public ResponseEntity<byte[]> getProfilePicture(@AuthenticationPrincipal User user,
                                                    @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        return ProfilePictureResponses.respond(profilePictureService, user, ifNoneMatch);
    }

    @DeleteMapping("/me/profile-picture")
    public ResponseEntity<Void> deleteProfilePicture(@AuthenticationPrincipal User user) {
        profilePictureService.delete(user);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/me/email-verification/resend")
    public ResponseEntity<Void> resendEmailVerification(@AuthenticationPrincipal User user) {
        emailVerificationService.resend(user);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "E-mail change — step 1: request",
            description = "Needs the current password. Sends a 6-digit code (valid 30 min) to the NEW address; "
                    + "the account e-mail does not change yet. 409 = account without password, or address in use.")
    @PostMapping("/me/email-change")
    public ResponseEntity<Void> requestEmailChange(@AuthenticationPrincipal User user,
                                                   @Valid @RequestBody RequestEmailChangeRequest request) {
        emailChangeService.requestChange(user, request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "E-mail change — step 2: confirm the code",
            description = "Switches the account to the new (verified) e-mail, notifies the old address and "
                    + "revokes the other sessions. 400 = invalid/expired code.")
    @PostMapping("/me/email-change/confirm")
    public ResponseEntity<UserResponse> confirmEmailChange(@AuthenticationPrincipal User user,
                                                           @Valid @RequestBody ConfirmEmailChangeRequest request) {
        return ResponseEntity.ok(emailChangeService.confirmChange(user, request));
    }

    @DeleteMapping("/me/email-change")
    public ResponseEntity<Void> cancelEmailChange(@AuthenticationPrincipal User user) {
        emailChangeService.cancelChange(user);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/me/phone")
    public ResponseEntity<Void> updatePhone(@AuthenticationPrincipal User user,
                                            @Valid @RequestBody UpdatePhoneRequest request) {
        phoneVerificationService.setPhoneAndSendOtp(user, request.phoneNumber());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/me/phone/verify")
    public ResponseEntity<Void> verifyPhone(@AuthenticationPrincipal User user,
                                            @Valid @RequestBody VerifyPhoneRequest request) {
        phoneVerificationService.verify(user, request.code());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me/whatsapp")
    public ResponseEntity<WhatsAppStatusResponse> whatsAppStatus(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(whatsAppOptInService.status(user));
    }

    @PatchMapping("/me/whatsapp")
    public ResponseEntity<WhatsAppStatusResponse> updateWhatsApp(@AuthenticationPrincipal User user,
                                                                @Valid @RequestBody UpdateWhatsAppRequest request) {
        return ResponseEntity.ok(whatsAppOptInService.updateWeeklyReport(user, request.weeklyReportEnabled()));
    }
}
