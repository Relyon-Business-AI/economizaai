package com.relyon.economizaai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.config.SecurityConfig;
import com.relyon.economizaai.dto.request.UpdateHomeLocationRequest;
import com.relyon.economizaai.dto.request.UpdateNotificationPreferencesRequest;
import com.relyon.economizaai.dto.request.UpdatePushTokenRequest;
import com.relyon.economizaai.dto.response.NotificationPreferenceResponse;
import com.relyon.economizaai.dto.response.UserResponse;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationChannel;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.model.enums.SubscriptionTier;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.UserService;
import com.relyon.economizaai.service.auth.EmailVerificationService;
import com.relyon.economizaai.service.auth.PhoneVerificationService;
import com.relyon.economizaai.service.notifications.NotificationPreferenceService;
import com.relyon.economizaai.service.notifications.SavingsService;
import com.relyon.economizaai.service.profile.ProfilePictureService;
import com.relyon.economizaai.service.subscription.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@Import(SecurityConfig.class)
class UserControllerCoverageTest {

    @Autowired private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean private UserService userService;
    @MockitoBean private LocalizedMessageService localizedMessageService;
    @MockitoBean private NotificationPreferenceService notificationPreferenceService;
    @MockitoBean private ProfilePictureService profilePictureService;
    @MockitoBean private EmailVerificationService emailVerificationService;
    @MockitoBean private PhoneVerificationService phoneVerificationService;
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private SavingsService savingsService;
    @MockitoBean private SubscriptionService subscriptionService;

    private User principal() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        return User.builder()
                .id(UUID.randomUUID())
                .name("John Doe")
                .email("john@test.com")
                .role(Role.USER)
                .subscriptionTier(SubscriptionTier.FREE)
                .household(household)
                .build();
    }

    private UserResponse userResponse() {
        return new UserResponse(UUID.randomUUID(), "John Doe", "john@test.com",
                Role.USER, SubscriptionTier.FREE, true, true, LocalDateTime.now(),
                new BigDecimal("-30.0277"), new BigDecimal("-51.2287"),
                null, null, null, null, null, LocalDateTime.now());
    }

    @Test
    void updateHomeLocation_returns200() throws Exception {
        when(userService.updateHomeLocation(any(User.class), any(UpdateHomeLocationRequest.class)))
                .thenReturn(userResponse());

        mockMvc.perform(patch("/api/v1/users/me/location")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateHomeLocationRequest(new BigDecimal("-30.0277"), new BigDecimal("-51.2287")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.homeLatitude").value(-30.0277));
    }

    @Test
    void updateHomeLocation_rejectsOutOfRangeLongitude() throws Exception {
        mockMvc.perform(patch("/api/v1/users/me/location")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateHomeLocationRequest(new BigDecimal("-30.0"), new BigDecimal("300")))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateHomeLocation_rejectsNullLatitude() throws Exception {
        mockMvc.perform(patch("/api/v1/users/me/location")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"longitude\": -51.0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updatePushToken_returnsOkStatus() throws Exception {
        mockMvc.perform(patch("/api/v1/users/me/push-token")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdatePushTokenRequest("fcm-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        verify(notificationPreferenceService).updatePushToken(any(User.class), any(UpdatePushTokenRequest.class));
    }

    @Test
    void notificationPreferences_returnsList() throws Exception {
        when(notificationPreferenceService.list(any(User.class))).thenReturn(List.of(
                new NotificationPreferenceResponse(NotificationType.PRICE_DROP, NotificationChannel.PUSH)));

        mockMvc.perform(get("/api/v1/users/me/notification-preferences")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("PRICE_DROP"))
                .andExpect(jsonPath("$[0].channel").value("PUSH"));
    }

    @Test
    void updateNotificationPreferences_returnsList() throws Exception {
        when(notificationPreferenceService.update(any(User.class), any(UpdateNotificationPreferencesRequest.class)))
                .thenReturn(List.of(new NotificationPreferenceResponse(NotificationType.SYSTEM, NotificationChannel.EMAIL)));

        var request = new UpdateNotificationPreferencesRequest(List.of(
                new UpdateNotificationPreferencesRequest.Preference(NotificationType.SYSTEM, NotificationChannel.EMAIL)));

        mockMvc.perform(put("/api/v1/users/me/notification-preferences")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].channel").value("EMAIL"));
    }

    @Test
    void updateNotificationPreferences_rejectsEmptyList() throws Exception {
        var request = new UpdateNotificationPreferencesRequest(List.of());

        mockMvc.perform(put("/api/v1/users/me/notification-preferences")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void uploadProfilePicture_returnsOkStatus() throws Exception {
        var file = new MockMultipartFile("file", "avatar.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/v1/users/me/profile-picture")
                        .file(file)
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        verify(profilePictureService).upload(any(User.class), any());
    }

    @Test
    void getProfilePicture_returnsBytesWithFallbackHeader() throws Exception {
        when(profilePictureService.read(any(User.class)))
                .thenReturn(new ProfilePictureService.ProfilePictureBytes(new byte[]{9, 8, 7}, "image/png", true));

        mockMvc.perform(get("/api/v1/users/me/profile-picture")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Profile-Picture-Fallback", "true"))
                .andExpect(header().string("Content-Type", "image/png"));
    }

    @Test
    void getProfilePicture_defaultsContentTypeWhenNull() throws Exception {
        when(profilePictureService.read(any(User.class)))
                .thenReturn(new ProfilePictureService.ProfilePictureBytes(new byte[]{1}, null, false));

        mockMvc.perform(get("/api/v1/users/me/profile-picture")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Profile-Picture-Fallback", "false"))
                .andExpect(header().string("Content-Type", "application/octet-stream"));
    }

    @Test
    void deleteProfilePicture_returnsNoContent() throws Exception {
        mockMvc.perform(delete("/api/v1/users/me/profile-picture")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isNoContent());

        verify(profilePictureService).delete(any(User.class));
    }

    @Test
    void resendEmailVerification_returnsNoContent() throws Exception {
        mockMvc.perform(post("/api/v1/users/me/email-verification/resend")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isNoContent());

        verify(emailVerificationService).resend(any(User.class));
    }

    @Test
    void resendEmailVerification_unauthenticatedIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/users/me/email-verification/resend"))
                .andExpect(status().isUnauthorized());
    }
}
