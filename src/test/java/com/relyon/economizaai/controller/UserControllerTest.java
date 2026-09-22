package com.relyon.economizaai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.config.SecurityConfig;
import com.relyon.economizaai.dto.request.ChangePasswordRequest;
import com.relyon.economizaai.dto.request.UpdateContributionRequest;
import com.relyon.economizaai.dto.request.UpdateDigestPreferencesRequest;
import com.relyon.economizaai.dto.request.UpdateUserRequest;
import com.relyon.economizaai.dto.response.DigestPreferencesResponse;
import com.relyon.economizaai.dto.response.HouseholdResponse;
import com.relyon.economizaai.dto.response.SavingsSummaryResponse;
import com.relyon.economizaai.dto.response.SubscriptionStatusResponse;
import com.relyon.economizaai.dto.response.UserDataExportResponse;
import com.relyon.economizaai.dto.response.UserResponse;
import com.relyon.economizaai.exception.InvalidCurrentPasswordException;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.DigestFrequency;
import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.model.enums.SubscriptionStatus;
import com.relyon.economizaai.model.enums.SubscriptionTier;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.UserService;
import com.relyon.economizaai.dto.request.UpdatePhoneRequest;
import com.relyon.economizaai.dto.request.VerifyPhoneRequest;
import com.relyon.economizaai.exception.InvalidPhoneNumberException;
import com.relyon.economizaai.exception.InvalidPhoneVerificationException;
import com.relyon.economizaai.service.notifications.NotificationPreferenceService;
import com.relyon.economizaai.service.notifications.SavingsService;
import com.relyon.economizaai.service.subscription.SubscriptionService;
import com.relyon.economizaai.service.auth.EmailVerificationService;
import com.relyon.economizaai.service.auth.PhoneVerificationService;
import com.relyon.economizaai.service.profile.ProfilePictureService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@WebMvcTest(UserController.class)
@Import(SecurityConfig.class)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @MockitoBean
    private LocalizedMessageService localizedMessageService;

    @MockitoBean
    private NotificationPreferenceService notificationPreferenceService;

    @MockitoBean
    private ProfilePictureService profilePictureService;

    @MockitoBean
    private EmailVerificationService emailVerificationService;

    @MockitoBean
    private PhoneVerificationService phoneVerificationService;

    @MockitoBean
    private SavingsService savingsService;

    @MockitoBean
    private SubscriptionService subscriptionService;

    private User buildUser() {
        var user = User.builder()
                .id(UUID.randomUUID())
                .name("John")
                .email("john@test.com")
                .password("encoded")
                .role(Role.USER)
                .subscriptionTier(SubscriptionTier.FREE)
                .contributionOptIn(true)
                .active(true)
                .build();
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        return user;
    }

    private UserResponse responseFor(User user, String name) {
        return new UserResponse(
                user.getId(),
                name,
                user.getEmail(),
                user.getRole(),
                user.getSubscriptionTier(),
                user.isContributionOptIn(),
                user.isEmailVerified(),
                user.getEmailVerifiedAt(),
                user.getHomeLatitude(),
                user.getHomeLongitude(),
                user.getRegistrationPlatform(),
                user.getLastPlatform(),
                user.getLastWebLoginAt(),
                user.getLastAndroidLoginAt(),
                user.getLastIosLoginAt(),
                user.getCreatedAt()
        );
    }

    @Test
    void getProfile_shouldReturn200() throws Exception {
        var user = buildUser();
        when(userService.getProfile(any(User.class))).thenReturn(responseFor(user, "John"));

        mockMvc.perform(get("/api/v1/users/me")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("John"))
                .andExpect(jsonPath("$.email").value("john@test.com"))
                .andExpect(jsonPath("$.subscriptionTier").value("FREE"));
    }

    @Test
    void getProfile_shouldReturn401WhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateProfile_shouldReturn200() throws Exception {
        var user = buildUser();
        var request = new UpdateUserRequest("New Name");
        when(userService.updateProfile(any(User.class), any(UpdateUserRequest.class)))
                .thenReturn(responseFor(user, "New Name"));

        mockMvc.perform(put("/api/v1/users/me")
                        .with(SecurityMockMvcRequestPostProcessors.user(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New Name"));
    }

    @Test
    void updateProfile_shouldReturn400ForBlankName() throws Exception {
        var user = buildUser();
        var request = new UpdateUserRequest("");

        mockMvc.perform(put("/api/v1/users/me")
                        .with(SecurityMockMvcRequestPostProcessors.user(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void changePassword_shouldReturn200OnSuccess() throws Exception {
        var user = buildUser();
        var request = new ChangePasswordRequest("currentPass", "newPassword123");
        when(localizedMessageService.translate("user.password.changed"))
                .thenReturn("Password changed successfully.");

        mockMvc.perform(put("/api/v1/users/me/password")
                        .with(SecurityMockMvcRequestPostProcessors.user(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password changed successfully."));
    }

    @Test
    void changePassword_shouldReturn400ForWrongCurrentPassword() throws Exception {
        var user = buildUser();
        var request = new ChangePasswordRequest("wrongPass", "newPassword123");
        doThrow(new InvalidCurrentPasswordException()).when(userService).changePassword(any(User.class), any(ChangePasswordRequest.class));

        mockMvc.perform(put("/api/v1/users/me/password")
                        .with(SecurityMockMvcRequestPostProcessors.user(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateContribution_returns200() throws Exception {
        var user = buildUser();
        var request = new UpdateContributionRequest(false);
        var response = new UserResponse(user.getId(), user.getName(), user.getEmail(),
                user.getRole(), user.getSubscriptionTier(), false, true, user.getEmailVerifiedAt(),
                null, null, null, null, null, null, null, user.getCreatedAt());
        when(userService.updateContribution(any(User.class), any(UpdateContributionRequest.class)))
                .thenReturn(response);

        mockMvc.perform(patch("/api/v1/users/me/contribution")
                        .with(SecurityMockMvcRequestPostProcessors.user(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contributionOptIn").value(false));
    }

    @Test
    void exportData_returns200WithJson() throws Exception {
        var user = buildUser();
        var ur = new UserResponse(user.getId(), user.getName(), user.getEmail(),
                user.getRole(), user.getSubscriptionTier(), true, true, user.getEmailVerifiedAt(),
                null, null, null, null, null, null, null, user.getCreatedAt());
        var hr = new HouseholdResponse(
                UUID.randomUUID(), "ABC123", LocalDateTime.now().plusHours(48),
                List.of(new HouseholdResponse.HouseholdMember(user.getId(), user.getName(), user.getEmail())),
                LocalDateTime.now());
        var accountExtras = new UserDataExportResponse.AccountExtras(
                null, null, true, null, true, null, false, null, null, null,
                null, null, null, null, null, null, null, null);
        var export = new UserDataExportResponse(
                ur, accountExtras, hr,
                List.of(), List.of(), List.of(), List.of(), null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                LocalDateTime.now());
        when(userService.exportData(any(User.class))).thenReturn(export);

        mockMvc.perform(get("/api/v1/users/me/export")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(user.getEmail()))
                .andExpect(jsonPath("$.household.inviteCode").value("ABC123"))
                .andExpect(jsonPath("$.receipts").isArray());
    }

    @Test
    void savings_returns200WithHouseholdTotals() throws Exception {
        var user = buildUser();
        when(savingsService.summarize(any(User.class)))
                .thenReturn(new SavingsSummaryResponse(
                        new BigDecimal("42.50"), 7, new BigDecimal("15.00")));

        mockMvc.perform(get("/api/v1/users/me/savings")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSavings").value(42.50))
                .andExpect(jsonPath("$.conversions").value(7))
                .andExpect(jsonPath("$.last30DaysSavings").value(15.00));
    }

    @Test
    void subscription_returns200WithLifecycleFields() throws Exception {
        var user = buildUser();
        var periodEnd = LocalDateTime.now().plusDays(12);
        when(subscriptionService.statusFor(any(User.class)))
                .thenReturn(new SubscriptionStatusResponse(
                        SubscriptionTier.PRO, SubscriptionStatus.ACTIVE, "revenuecat", periodEnd));

        mockMvc.perform(get("/api/v1/users/me/subscription")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value("PRO"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.provider").value("revenuecat"));
    }

    @Test
    void subscription_returns200WithNullsForFreeUser() throws Exception {
        var user = buildUser();
        when(subscriptionService.statusFor(any(User.class)))
                .thenReturn(new SubscriptionStatusResponse(SubscriptionTier.FREE, null, null, null));

        mockMvc.perform(get("/api/v1/users/me/subscription")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value("FREE"))
                .andExpect(jsonPath("$.status").isEmpty());
    }

    @Test
    void subscription_returns401WhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/users/me/subscription"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void savings_returns200WithZeroWhenNoConversions() throws Exception {
        var user = buildUser();
        when(savingsService.summarize(any(User.class)))
                .thenReturn(new SavingsSummaryResponse(
                        BigDecimal.ZERO, 0, BigDecimal.ZERO));

        mockMvc.perform(get("/api/v1/users/me/savings")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversions").value(0));
    }

    @Test
    void savings_returns401WhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/users/me/savings"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updatePhone_returns204OnValidE164() throws Exception {
        var user = buildUser();
        var request = new UpdatePhoneRequest("+5551999999999");

        mockMvc.perform(patch("/api/v1/users/me/phone")
                        .with(SecurityMockMvcRequestPostProcessors.user(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());
    }

    @Test
    void updatePhone_returns400ForMalformedNumber() throws Exception {
        var user = buildUser();
        var request = new UpdatePhoneRequest("5551999999999");

        mockMvc.perform(patch("/api/v1/users/me/phone")
                        .with(SecurityMockMvcRequestPostProcessors.user(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updatePhone_returns400WhenServiceRejectsNumber() throws Exception {
        var user = buildUser();
        var request = new UpdatePhoneRequest("+5551999999999");
        doThrow(new InvalidPhoneNumberException())
                .when(phoneVerificationService).setPhoneAndSendOtp(any(User.class), any(String.class));

        mockMvc.perform(patch("/api/v1/users/me/phone")
                        .with(SecurityMockMvcRequestPostProcessors.user(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void verifyPhone_returns204OnCorrectCode() throws Exception {
        var user = buildUser();
        var request = new VerifyPhoneRequest("123456");

        mockMvc.perform(post("/api/v1/users/me/phone/verify")
                        .with(SecurityMockMvcRequestPostProcessors.user(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());
    }

    @Test
    void verifyPhone_returns400OnWrongOrExpiredCode() throws Exception {
        var user = buildUser();
        var request = new VerifyPhoneRequest("000000");
        doThrow(new InvalidPhoneVerificationException())
                .when(phoneVerificationService).verify(any(User.class), any(String.class));

        mockMvc.perform(post("/api/v1/users/me/phone/verify")
                        .with(SecurityMockMvcRequestPostProcessors.user(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteAccount_returns200() throws Exception {
        var user = buildUser();
        when(localizedMessageService.translate("user.account.deleted"))
                .thenReturn("Conta excluida.");

        mockMvc.perform(delete("/api/v1/users/me")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Conta excluida."));
    }

    @Test
    void getDigestPreferences_returns200() throws Exception {
        var user = buildUser();
        when(notificationPreferenceService.getDigestPreferences(any(User.class)))
                .thenReturn(new DigestPreferencesResponse(DigestFrequency.DAILY, 17));

        mockMvc.perform(get("/api/v1/users/me/digest-preferences")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.frequency").value("DAILY"))
                .andExpect(jsonPath("$.sendHour").value(17));
    }

    @Test
    void getDigestPreferences_returns401WhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/users/me/digest-preferences"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateDigestPreferences_returns200() throws Exception {
        var user = buildUser();
        var request = new UpdateDigestPreferencesRequest(DigestFrequency.WEEKLY, 8);
        when(notificationPreferenceService.updateDigestPreferences(any(User.class), any()))
                .thenReturn(new DigestPreferencesResponse(DigestFrequency.WEEKLY, 8));

        mockMvc.perform(put("/api/v1/users/me/digest-preferences")
                        .with(SecurityMockMvcRequestPostProcessors.user(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.frequency").value("WEEKLY"))
                .andExpect(jsonPath("$.sendHour").value(8));
    }

    @Test
    void updateDigestPreferences_acceptsNullSendHour() throws Exception {
        var user = buildUser();
        var request = new UpdateDigestPreferencesRequest(DigestFrequency.DAILY, null);
        when(notificationPreferenceService.updateDigestPreferences(any(User.class), any()))
                .thenReturn(new DigestPreferencesResponse(DigestFrequency.DAILY, null));

        mockMvc.perform(put("/api/v1/users/me/digest-preferences")
                        .with(SecurityMockMvcRequestPostProcessors.user(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.frequency").value("DAILY"));
    }

    @Test
    void updateDigestPreferences_returns400WhenHourOutOfRange() throws Exception {
        var user = buildUser();
        var request = new UpdateDigestPreferencesRequest(DigestFrequency.DAILY, 24);

        mockMvc.perform(put("/api/v1/users/me/digest-preferences")
                        .with(SecurityMockMvcRequestPostProcessors.user(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateDigestPreferences_returns400WhenFrequencyMissing() throws Exception {
        var user = buildUser();

        mockMvc.perform(put("/api/v1/users/me/digest-preferences")
                        .with(SecurityMockMvcRequestPostProcessors.user(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sendHour\":10}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateDigestPreferences_returns401WhenUnauthenticated() throws Exception {
        var request = new UpdateDigestPreferencesRequest(DigestFrequency.DAILY, 10);

        mockMvc.perform(put("/api/v1/users/me/digest-preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }
}
