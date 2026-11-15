package com.relyon.economizaai.controller;

import com.relyon.economizaai.config.SecurityConfig;
import com.relyon.economizaai.dto.request.UpdateNotificationSettingsRequest;
import com.relyon.economizaai.dto.response.NotificationSettingsResponse;
import com.relyon.economizaai.dto.response.NotificationSettingsResponse.Notice;
import com.relyon.economizaai.dto.response.NotificationSettingsResponse.Option;
import com.relyon.economizaai.dto.response.NotificationSettingsResponse.WhatsApp;
import com.relyon.economizaai.exception.DomainException;
import com.relyon.economizaai.exception.InvalidNotificationSettingsException;
import com.relyon.economizaai.exception.WhatsAppNotConnectedException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.DigestFrequency;
import com.relyon.economizaai.model.enums.NotificationChannel;
import com.relyon.economizaai.model.enums.NotificationNotice;
import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.model.enums.SpendingReportFrequency;
import com.relyon.economizaai.model.enums.SubscriptionTier;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.UserService;
import com.relyon.economizaai.service.auth.EmailChangeService;
import com.relyon.economizaai.service.auth.EmailVerificationService;
import com.relyon.economizaai.service.auth.PhoneVerificationService;
import com.relyon.economizaai.service.geo.CepLocationService;
import com.relyon.economizaai.service.notifications.NotificationPreferenceService;
import com.relyon.economizaai.service.notifications.SavingsService;
import com.relyon.economizaai.service.notifications.UserNotificationSettingsService;
import com.relyon.economizaai.service.notifications.WhatsAppOptInService;
import com.relyon.economizaai.service.profile.ProfilePictureService;
import com.relyon.economizaai.service.profile.StoreReviewPromptService;
import com.relyon.economizaai.service.subscription.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@Import(SecurityConfig.class)
class UserNotificationSettingsControllerTest {

    private static final String SETTINGS_URL = "/api/v1/users/me/notification-settings";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private UserNotificationSettingsService userNotificationSettingsService;
    @MockitoBean private UserService userService;
    @MockitoBean private LocalizedMessageService localizedMessageService;
    @MockitoBean private NotificationPreferenceService notificationPreferenceService;
    @MockitoBean private ProfilePictureService profilePictureService;
    @MockitoBean private StoreReviewPromptService storeReviewPromptService;
    @MockitoBean private CepLocationService cepLocationService;
    @MockitoBean private EmailVerificationService emailVerificationService;
    @MockitoBean private EmailChangeService emailChangeService;
    @MockitoBean private PhoneVerificationService phoneVerificationService;
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private SavingsService savingsService;
    @MockitoBean private SubscriptionService subscriptionService;
    @MockitoBean private WhatsAppOptInService whatsAppOptInService;

    private User principal() {
        return User.builder()
                .id(UUID.randomUUID())
                .name("Maria")
                .email("maria@economizaai.app")
                .role(Role.USER)
                .subscriptionTier(SubscriptionTier.FREE)
                .household(Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build())
                .build();
    }

    private static NotificationSettingsResponse settingsOfARegularUser() {
        return new NotificationSettingsResponse(
                "maria@economizaai.app",
                new WhatsApp(false, false, null, null),
                DigestFrequency.DAILY,
                List.of(SpendingReportFrequency.WEEKLY),
                List.of(
                        new Notice(NotificationNotice.RECEIPT_READY, List.of(new Option(NotificationChannel.PUSH, true))),
                        new Notice(NotificationNotice.PRICE_ALERTS, List.of(
                                new Option(NotificationChannel.PUSH, true),
                                new Option(NotificationChannel.EMAIL, false))),
                        new Notice(NotificationNotice.ACCOUNT, List.of(
                                new Option(NotificationChannel.PUSH, true),
                                new Option(NotificationChannel.EMAIL, false)))));
    }

    private ResultActions patchSettings(String body) throws Exception {
        return mockMvc.perform(patch(SETTINGS_URL)
                .with(SecurityMockMvcRequestPostProcessors.user(principal()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void updateIsRejectedWith(DomainException rejection, String localizedMessage) {
        when(userNotificationSettingsService.update(any(User.class), any(UpdateNotificationSettingsRequest.class)))
                .thenThrow(rejection);
        when(localizedMessageService.translate(rejection)).thenReturn(localizedMessage);
    }

    // ---- GET ----

    @Test
    void getReturnsTheContractTheAppIsWrittenAgainst() throws Exception {
        when(userNotificationSettingsService.openSettings(any(User.class))).thenReturn(settingsOfARegularUser());

        mockMvc.perform(get(SETTINGS_URL).with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("maria@economizaai.app"))
                .andExpect(jsonPath("$.whatsApp.available").value(false))
                .andExpect(jsonPath("$.whatsApp.connected").value(false))
                .andExpect(jsonPath("$.whatsApp.maskedNumber").value(nullValue()))
                .andExpect(jsonPath("$.whatsApp.confirmLink").value(nullValue()))
                .andExpect(jsonPath("$.whatsApp.length()").value(4))
                .andExpect(jsonPath("$.digestFrequency").value("DAILY"))
                .andExpect(jsonPath("$.reportFrequencies", contains("WEEKLY")))
                .andExpect(jsonPath("$.notices", hasSize(3)))
                .andExpect(jsonPath("$.notices[0].key").value("RECEIPT_READY"))
                .andExpect(jsonPath("$.notices[0].options", hasSize(1)))
                .andExpect(jsonPath("$.notices[0].options[0].channel").value("PUSH"))
                .andExpect(jsonPath("$.notices[0].options[0].enabled").value(true))
                .andExpect(jsonPath("$.notices[0].options[0].length()").value(2))
                .andExpect(jsonPath("$.notices[1].key").value("PRICE_ALERTS"))
                .andExpect(jsonPath("$.notices[1].options[1].channel").value("EMAIL"))
                .andExpect(jsonPath("$.notices[1].options[1].enabled").value(false))
                .andExpect(jsonPath("$.notices[2].key").value("ACCOUNT"))
                .andExpect(jsonPath("$.notices[2].options[1].channel").value("EMAIL"))
                .andExpect(jsonPath("$.notices[2].options[1].enabled").value(false));
    }

    @Test
    void getReturnsTheWhatsAppBlockOfAnAccountItIsAvailableTo() throws Exception {
        when(userNotificationSettingsService.openSettings(any(User.class))).thenReturn(new NotificationSettingsResponse(
                "admin@economizaai.app",
                new WhatsApp(true, false, null, "https://wa.me/14063448189?text=CONFIRMAR-tok"),
                DigestFrequency.WEEKLY,
                List.of(SpendingReportFrequency.WEEKLY, SpendingReportFrequency.MONTHLY),
                List.of(new Notice(NotificationNotice.WEEKLY_REPORT, List.of(
                        new Option(NotificationChannel.EMAIL, false),
                        new Option(NotificationChannel.WHATSAPP, false))))));

        mockMvc.perform(get(SETTINGS_URL).with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.whatsApp.available").value(true))
                .andExpect(jsonPath("$.whatsApp.connected").value(false))
                .andExpect(jsonPath("$.whatsApp.confirmLink").value("https://wa.me/14063448189?text=CONFIRMAR-tok"))
                .andExpect(jsonPath("$.digestFrequency").value("WEEKLY"))
                .andExpect(jsonPath("$.reportFrequencies", contains("WEEKLY", "MONTHLY")))
                .andExpect(jsonPath("$.notices[0].options[1].channel").value("WHATSAPP"));
    }

    @Test
    void getIsRejectedWithoutAuthentication() throws Exception {
        mockMvc.perform(get(SETTINGS_URL)).andExpect(status().isUnauthorized());

        verifyNoInteractions(userNotificationSettingsService);
    }

    // ---- PATCH ----

    @Test
    void patchPassesTheChangesOnAndReturnsTheFullSettings() throws Exception {
        when(userNotificationSettingsService.update(any(User.class), any(UpdateNotificationSettingsRequest.class)))
                .thenReturn(settingsOfARegularUser());

        patchSettings("""
                { "options": [ { "key": "PRICE_ALERTS", "channel": "EMAIL", "enabled": true } ],
                  "digestFrequency": "WEEKLY",
                  "reportFrequencies": ["WEEKLY", "MONTHLY"] }
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("maria@economizaai.app"))
                .andExpect(jsonPath("$.reportFrequencies", contains("WEEKLY")))
                .andExpect(jsonPath("$.notices", hasSize(3)));

        var sent = ArgumentCaptor.forClass(UpdateNotificationSettingsRequest.class);
        verify(userNotificationSettingsService).update(any(User.class), sent.capture());
        assertThat(sent.getValue().digestFrequency()).isEqualTo("WEEKLY");
        assertThat(sent.getValue().reportFrequencies()).containsExactly("WEEKLY", "MONTHLY");
        assertThat(sent.getValue().options()).containsExactly(
                new UpdateNotificationSettingsRequest.OptionChange("PRICE_ALERTS", "EMAIL", true));
    }

    @Test
    void patchAcceptsABodyWithNoFields() throws Exception {
        when(userNotificationSettingsService.update(any(User.class), any(UpdateNotificationSettingsRequest.class)))
                .thenReturn(settingsOfARegularUser());

        patchSettings("{}").andExpect(status().isOk());

        var sent = ArgumentCaptor.forClass(UpdateNotificationSettingsRequest.class);
        verify(userNotificationSettingsService).update(any(User.class), sent.capture());
        assertThat(sent.getValue().options()).isNull();
        assertThat(sent.getValue().digestFrequency()).isNull();
        assertThat(sent.getValue().reportFrequencies()).isNull();
    }

    @Test
    void patchPassesAnUnknownReportFrequencyOnAsTextAndAnswers400() throws Exception {
        updateIsRejectedWith(InvalidNotificationSettingsException.invalidReportFrequencies(),
                "Escolha pelo menos uma frequência para o relatório: semanal ou mensal.");

        patchSettings("{ \"reportFrequencies\": [\"YEARLY\"] }")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Escolha pelo menos uma frequência para o relatório: semanal ou mensal."));
    }

    @Test
    void patchWithAnEmptyReportFrequencySelectionIs400() throws Exception {
        updateIsRejectedWith(InvalidNotificationSettingsException.invalidReportFrequencies(),
                "Escolha pelo menos uma frequência para o relatório: semanal ou mensal.");

        patchSettings("{ \"reportFrequencies\": [] }")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Escolha pelo menos uma frequência para o relatório: semanal ou mensal."));

        var sent = ArgumentCaptor.forClass(UpdateNotificationSettingsRequest.class);
        verify(userNotificationSettingsService).update(any(User.class), sent.capture());
        assertThat(sent.getValue().reportFrequencies()).isEmpty();
    }

    @Test
    void patchWithAChannelTheNoticeDoesNotOfferIs400() throws Exception {
        updateIsRejectedWith(InvalidNotificationSettingsException.channelNotOffered(),
                "Esse aviso não pode ser enviado por esse canal.");

        patchSettings("{ \"options\": [ { \"key\": \"STOCKOUT\", \"channel\": \"EMAIL\", \"enabled\": true } ] }")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Esse aviso não pode ser enviado por esse canal."));
    }

    @Test
    void patchWithWhatsAppWhenNotAvailableIs400() throws Exception {
        updateIsRejectedWith(InvalidNotificationSettingsException.whatsAppUnavailable(),
                "O WhatsApp ainda não está disponível para a sua conta.");

        patchSettings("{ \"options\": [ { \"key\": \"PRICE_ALERTS\", \"channel\": \"WHATSAPP\", \"enabled\": true } ] }")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("O WhatsApp ainda não está disponível para a sua conta."));
    }

    @Test
    void patchTurningWhatsAppOnWhenNotConnectedIs409() throws Exception {
        updateIsRejectedWith(new WhatsAppNotConnectedException(),
                "Conecte seu WhatsApp antes de ligar os avisos por lá.");

        patchSettings("{ \"options\": [ { \"key\": \"PRICE_ALERTS\", \"channel\": \"WHATSAPP\", \"enabled\": true } ] }")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Conecte seu WhatsApp antes de ligar os avisos por lá."));
    }

    @Test
    void patchWithAnUnknownNoticeKeyIs400() throws Exception {
        updateIsRejectedWith(InvalidNotificationSettingsException.unknownNotice("PROMO"), "Aviso desconhecido: PROMO.");

        patchSettings("{ \"options\": [ { \"key\": \"PROMO\", \"channel\": \"PUSH\", \"enabled\": true } ] }")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Aviso desconhecido: PROMO."));
    }

    @Test
    void patchWithAnUnknownChannelIs400() throws Exception {
        updateIsRejectedWith(InvalidNotificationSettingsException.unknownChannel("TELEGRAM"),
                "Canal desconhecido: TELEGRAM.");

        patchSettings("{ \"options\": [ { \"key\": \"STOCKOUT\", \"channel\": \"TELEGRAM\", \"enabled\": true } ] }")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Canal desconhecido: TELEGRAM."));
    }

    @Test
    void patchWithAnInvalidDigestFrequencyIs400() throws Exception {
        updateIsRejectedWith(InvalidNotificationSettingsException.invalidDigestFrequency(),
                "Frequência inválida. Escolha diário ou semanal.");

        patchSettings("{ \"digestFrequency\": \"OFF\" }")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Frequência inválida. Escolha diário ou semanal."));
    }

    @Test
    void patchWithMalformedJsonIs400() throws Exception {
        patchSettings("{ \"options\": \"not-a-list\" }").andExpect(status().isBadRequest());

        verifyNoInteractions(userNotificationSettingsService);
    }

    @Test
    void patchIsRejectedWithoutAuthentication() throws Exception {
        mockMvc.perform(patch(SETTINGS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userNotificationSettingsService);
    }
}
