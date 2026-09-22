package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.request.ChangePasswordRequest;
import com.relyon.economizaai.dto.request.UpdateContributionRequest;
import com.relyon.economizaai.dto.request.UpdateDigestPreferencesRequest;
import com.relyon.economizaai.dto.request.UpdateHomeLocationRequest;
import com.relyon.economizaai.dto.request.UpdateNotificationPreferencesRequest;
import com.relyon.economizaai.dto.request.UpdatePhoneRequest;
import com.relyon.economizaai.dto.request.UpdatePushTokenRequest;
import com.relyon.economizaai.dto.request.UpdateUserRequest;
import com.relyon.economizaai.dto.request.VerifyPhoneRequest;
import com.relyon.economizaai.dto.response.DigestPreferencesResponse;
import com.relyon.economizaai.dto.response.NotificationPreferenceResponse;
import com.relyon.economizaai.dto.response.SavingsSummaryResponse;
import com.relyon.economizaai.dto.response.SubscriptionStatusResponse;
import com.relyon.economizaai.dto.response.UserDataExportResponse;
import com.relyon.economizaai.dto.response.UserResponse;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.UserService;
import com.relyon.economizaai.service.auth.EmailVerificationService;
import com.relyon.economizaai.service.auth.PhoneVerificationService;
import com.relyon.economizaai.service.notifications.NotificationPreferenceService;
import com.relyon.economizaai.service.notifications.SavingsService;
import com.relyon.economizaai.service.profile.ProfilePictureService;
import com.relyon.economizaai.service.subscription.SubscriptionService;
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
    private final PhoneVerificationService phoneVerificationService;
    private final SavingsService savingsService;
    private final SubscriptionService subscriptionService;

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

    @PatchMapping("/me/push-token")
    public ResponseEntity<Map<String, String>> updatePushToken(@AuthenticationPrincipal User user,
                                                               @Valid @RequestBody UpdatePushTokenRequest request) {
        notificationPreferenceService.updatePushToken(user, request);
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
    public ResponseEntity<Map<String, String>> deleteAccount(@AuthenticationPrincipal User user) {
        userService.deleteAccount(user);
        return ResponseEntity.ok(Map.of("message", messageService.translate("user.account.deleted")));
    }

    @PostMapping(value = "/me/profile-picture", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> uploadProfilePicture(@AuthenticationPrincipal User user,
                                                                    @RequestParam("file") MultipartFile file) {
        profilePictureService.upload(user, file);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping("/me/profile-picture")
    public ResponseEntity<byte[]> getProfilePicture(@AuthenticationPrincipal User user) {
        var pic = profilePictureService.read(user);
        // X-Profile-Picture-Fallback lets the FE distinguish a generated
        // initials avatar from a user-uploaded photo without a separate
        // metadata round-trip (e.g. to gate "edit photo" vs "upload photo"
        // copy in the UI).
        return ResponseEntity.ok()
                .header("X-Profile-Picture-Fallback", String.valueOf(pic.fallback()))
                .contentType(MediaType.parseMediaType(pic.contentType() == null ? "application/octet-stream" : pic.contentType()))
                .body(pic.bytes());
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
}
