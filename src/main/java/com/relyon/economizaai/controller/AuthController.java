package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.request.AppleLoginRequest;
import com.relyon.economizaai.dto.request.ForgotPasswordRequest;
import com.relyon.economizaai.dto.request.GoogleLoginRequest;
import com.relyon.economizaai.dto.request.LoginRequest;
import com.relyon.economizaai.dto.request.RefreshTokenRequest;
import com.relyon.economizaai.dto.request.RegisterRequest;
import com.relyon.economizaai.dto.request.ResetPasswordRequest;
import com.relyon.economizaai.dto.request.VerifyEmailRequest;
import com.relyon.economizaai.dto.request.VerifyResetCodeRequest;
import com.relyon.economizaai.dto.response.AuthResponse;
import com.relyon.economizaai.dto.response.UserResponse;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.UserService;
import com.relyon.economizaai.service.auth.EmailVerificationService;
import com.relyon.economizaai.service.auth.PasswordResetService;
import com.relyon.economizaai.service.auth.RefreshTokenService;
import com.relyon.economizaai.service.auth.oauth.SocialLoginService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "Registration, login, password reset, and email verification")
public class AuthController {

    private final UserService userService;
    private final PasswordResetService passwordResetService;
    private final EmailVerificationService emailVerificationService;
    private final RefreshTokenService refreshTokenService;
    private final JwtService jwtService;
    private final SocialLoginService socialLoginService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(userService.login(request));
    }

    @PostMapping("/google")
    public ResponseEntity<AuthResponse> google(@Valid @RequestBody GoogleLoginRequest request) {
        return ResponseEntity.ok(socialLoginService.loginWithGoogle(request));
    }

    @PostMapping("/apple")
    public ResponseEntity<AuthResponse> apple(@Valid @RequestBody AppleLoginRequest request) {
        return ResponseEntity.ok(socialLoginService.loginWithApple(request));
    }

    @Operation(summary = "Password reset — step 1: request a code",
            description = "Emails a 6-digit code to the account (valid 60 min). Always returns 204, even for an " +
                    "unknown or social-only email, so it never reveals which addresses exist.")
    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        passwordResetService.requestReset(request);
        // Always 204 — never reveal whether the email exists.
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Password reset — step 2: verify the code",
            description = "Validates the emailed code WITHOUT consuming it, so the app can advance to the " +
                    "new-password screen. 204 = valid; 400 = invalid/expired/used code.")
    @PostMapping("/verify-reset-code")
    public ResponseEntity<Void> verifyResetCode(@Valid @RequestBody VerifyResetCodeRequest request) {
        passwordResetService.verifyCode(request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Password reset — step 3: set the new password",
            description = "Consumes the code and sets the new password. The code is single-use. " +
                    "204 = done; 400 = invalid/expired/used code.")
    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.resetPassword(request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Email verification: confirm the 6-digit code",
            description = "Consumes the code emailed at signup/resend. Idempotent for already-verified "
                    + "accounts. 204 = verified; 400 = invalid/expired/locked code.")
    @PostMapping("/verify-email")
    public ResponseEntity<Void> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        emailVerificationService.verify(request.email(), request.code());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        var user = refreshTokenService.rotate(request.refreshToken());
        var accessToken = jwtService.generateToken(user);
        var newRefresh = refreshTokenService.issue(user);
        return ResponseEntity.ok(new AuthResponse(accessToken, newRefresh, UserResponse.from(user), false, null));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
        refreshTokenService.revoke(request.refreshToken());
        // Idempotent — unknown / already-invalid tokens still return 204.
        return ResponseEntity.noContent().build();
    }
}
