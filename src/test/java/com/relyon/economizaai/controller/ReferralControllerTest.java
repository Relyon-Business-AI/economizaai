package com.relyon.economizaai.controller;

import com.relyon.economizaai.config.SecurityConfig;
import com.relyon.economizaai.dto.response.ReferralStatusResponse;
import com.relyon.economizaai.exception.ReferralCodeRejectedException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.referral.ReferralService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReferralController.class)
@Import(SecurityConfig.class)
class ReferralControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private ReferralService referralService;
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private LocalizedMessageService localizedMessageService;

    private User buildUser() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        return User.builder().id(UUID.randomUUID()).name("Maria").email("test48213@economizaai.app")
                .role(Role.USER).household(household).active(true).build();
    }

    @Test
    void me_returnsTheContractShape() throws Exception {
        when(referralService.statusFor(any(User.class))).thenReturn(new ReferralStatusResponse(
                "ABC2345", "https://economizaai.app/?ref=ABC2345", 4, 2, 3, true, false, true));

        mockMvc.perform(get("/api/v1/referrals/me")
                        .with(SecurityMockMvcRequestPostProcessors.user(buildUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("ABC2345"))
                .andExpect(jsonPath("$.shareUrl").value("https://economizaai.app/?ref=ABC2345"))
                .andExpect(jsonPath("$.invitedCount").value(4))
                .andExpect(jsonPath("$.qualifiedCount").value(2))
                .andExpect(jsonPath("$.rewardedMonths").value(3))
                .andExpect(jsonPath("$.firstRewardUsed").value(true))
                .andExpect(jsonPath("$.referredBy").value(false))
                .andExpect(jsonPath("$.canRedeemCode").value(true));
    }

    @Test
    void me_requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/referrals/me")).andExpect(status().is4xxClientError());
    }

    @Test
    void redeem_returnsNoContent() throws Exception {
        mockMvc.perform(post("/api/v1/referrals/redeem")
                        .with(SecurityMockMvcRequestPostProcessors.user(buildUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"ABC2345\"}"))
                .andExpect(status().isNoContent());

        verify(referralService).redeem(any(User.class), eq("ABC2345"));
    }

    @Test
    void redeem_blankCodeIsABadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/referrals/redeem")
                        .with(SecurityMockMvcRequestPostProcessors.user(buildUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void redeem_invalidOrOwnCodeIsABadRequestWithTheLocalizedMessage() throws Exception {
        doThrow(ReferralCodeRejectedException.invalidCode()).when(referralService).redeem(any(User.class), eq("NOPE234"));
        when(localizedMessageService.translate(any(ReferralCodeRejectedException.class)))
                .thenReturn("Esse código de indicação não existe.");

        mockMvc.perform(post("/api/v1/referrals/redeem")
                        .with(SecurityMockMvcRequestPostProcessors.user(buildUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"NOPE234\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Esse código de indicação não existe."));
    }

    @Test
    void redeem_tooLateOrAlreadyReferredIsAConflict() throws Exception {
        doThrow(ReferralCodeRejectedException.tooLate()).when(referralService).redeem(any(User.class), eq("ABC2345"));

        mockMvc.perform(post("/api/v1/referrals/redeem")
                        .with(SecurityMockMvcRequestPostProcessors.user(buildUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"ABC2345\"}"))
                .andExpect(status().isConflict());
    }
}
