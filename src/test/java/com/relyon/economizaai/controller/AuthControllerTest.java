package com.relyon.economizaai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.config.SecurityConfig;
import com.relyon.economizaai.dto.request.GoogleLoginRequest;
import com.relyon.economizaai.dto.request.LoginRequest;
import com.relyon.economizaai.dto.request.RegisterRequest;
import com.relyon.economizaai.dto.response.AuthResponse;
import com.relyon.economizaai.dto.response.UserResponse;
import com.relyon.economizaai.exception.EmailAlreadyExistsException;
import com.relyon.economizaai.exception.InvalidCredentialsException;
import com.relyon.economizaai.exception.SocialAccountLoginException;
import com.relyon.economizaai.model.enums.AuthProvider;
import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.model.enums.SubscriptionTier;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.UserService;
import com.relyon.economizaai.service.auth.EmailVerificationService;
import com.relyon.economizaai.service.auth.PasswordResetService;
import com.relyon.economizaai.service.auth.RefreshTokenService;
import com.relyon.economizaai.service.auth.oauth.SocialLoginService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
class AuthControllerTest {

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
    private PasswordResetService passwordResetService;

    @MockitoBean
    private EmailVerificationService emailVerificationService;

    @MockitoBean
    private RefreshTokenService refreshTokenService;

    @MockitoBean
    private SocialLoginService socialLoginService;

    private UserResponse sampleUserResponse() {
        return new UserResponse(
                UUID.randomUUID(),
                "John",
                "john@test.com",
                Role.USER,
                SubscriptionTier.FREE,
                true,
                true,
                LocalDateTime.now(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                LocalDateTime.now()
        );
    }

    @Test
    void register_shouldReturn201WithToken() throws Exception {
        var request = new RegisterRequest("John", "john@test.com", "password123", "1.0", "1.0", null);
        var promoValidUntil = LocalDateTime.now().plusMonths(3);
        var response = new AuthResponse("jwt-token", "refresh-token", sampleUserResponse(), true, promoValidUntil);
        when(userService.register(any(RegisterRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value("jwt-token"))
                .andExpect(jsonPath("$.user.name").value("John"))
                .andExpect(jsonPath("$.user.subscriptionTier").value("FREE"))
                .andExpect(jsonPath("$.signupPromoGranted").value(true))
                .andExpect(jsonPath("$.signupPromoValidUntil").exists());
    }

    @Test
    void register_shouldReturn409WhenEmailExists() throws Exception {
        var request = new RegisterRequest("John", "john@test.com", "password123", "1.0", "1.0", null);
        when(userService.register(any(RegisterRequest.class)))
                .thenThrow(new EmailAlreadyExistsException("john@test.com"));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    void register_shouldReturn400ForInvalidInput() throws Exception {
        var request = new RegisterRequest("", "not-an-email", "short", "", "", null);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_shouldReturn200WithToken() throws Exception {
        var request = new LoginRequest("john@test.com", "password123", null);
        var response = new AuthResponse("jwt-token", "refresh-token", sampleUserResponse(), false, null);
        when(userService.login(any(LoginRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("jwt-token"));
    }

    @Test
    void google_shouldReturn200WithToken() throws Exception {
        var request = new GoogleLoginRequest("google-id-token", null);
        var response = new AuthResponse("jwt-token", "refresh-token", sampleUserResponse(), false, null);
        when(socialLoginService.loginWithGoogle(any(GoogleLoginRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("jwt-token"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-token"))
                // FE-facing user shape carries verification state (added for social login).
                .andExpect(jsonPath("$.user.emailVerified").value(true))
                .andExpect(jsonPath("$.user.emailVerifiedAt").exists());
    }

    @Test
    void google_shouldReturn400WhenTokenBlank() throws Exception {
        var request = new GoogleLoginRequest("", null);

        mockMvc.perform(post("/api/v1/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_shouldReturn401ForInvalidCredentials() throws Exception {
        var request = new LoginRequest("john@test.com", "wrong", null);
        when(userService.login(any(LoginRequest.class))).thenThrow(new InvalidCredentialsException());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_socialAccount_returns409WithProviderForButtonHighlight() throws Exception {
        var request = new LoginRequest("jane@test.com", "whatever", null);
        when(userService.login(any(LoginRequest.class)))
                .thenThrow(new SocialAccountLoginException(AuthProvider.GOOGLE));
        when(localizedMessageService.translate(any(SocialAccountLoginException.class)))
                .thenReturn("Esta conta usa login com Google.");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                // structured provider so the FE can highlight the right button
                .andExpect(jsonPath("$.errors.provider").value("GOOGLE"))
                .andExpect(jsonPath("$.message").value("Esta conta usa login com Google."));
    }
}
