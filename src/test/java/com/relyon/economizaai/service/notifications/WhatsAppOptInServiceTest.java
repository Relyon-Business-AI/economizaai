package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.notifications.WhatsAppReportService.WeeklyReport;
import com.relyon.economizaai.service.notifications.twilio.TwilioMessageClient;
import com.relyon.economizaai.service.paidapi.PaidApiGuardService;
import com.relyon.economizaai.exception.PaidApiBudgetExceededException;
import com.relyon.economizaai.exception.PaidApiQuotaExceededException;
import com.relyon.economizaai.exception.WhatsAppOptInRequiredException;
import com.relyon.economizaai.model.enums.PaidApiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WhatsAppOptInServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final TwilioMessageClient twilioClient = mock(TwilioMessageClient.class);
    private final WhatsAppReportService reportService = mock(WhatsAppReportService.class);
    private final ReportChartTokenService chartTokenService = mock(ReportChartTokenService.class);
    private final LocalizedMessageService messageService = mock(LocalizedMessageService.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private final PaidApiGuardService paidApiGuardService = mock(PaidApiGuardService.class);

    private WhatsAppOptInService service;

    @BeforeEach
    void setUp() {
        // run the tx callback inline so handleInbound's DB work executes in unit tests
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                invocation.getArgument(0, TransactionCallback.class).doInTransaction(null));
        service = new WhatsAppOptInService(userRepository, twilioClient, reportService, chartTokenService,
                messageService, transactionTemplate, paidApiGuardService, "whatsapp:+14063448189", "https://api.economizaai.app");
    }

    private User userWithToken(String token) {
        return User.builder().email("u@economizaai.app").whatsAppLinkToken(token).build();
    }

    @Test
    void handleInbound_confirmar_linksNumberOptsInAndReplies() {
        var user = userWithToken("tok123");
        when(userRepository.findByWhatsAppLinkToken("tok123")).thenReturn(Optional.of(user));
        when(reportService.buildWeeklyReport(user)).thenReturn(new WeeklyReport("SEU RESUMO", false));
        when(messageService.translate(eq("whatsapp.optin.confirmed"), any(Locale.class))).thenReturn("Vinculado!");

        service.handleInbound("+5551997513229", "CONFIRMAR-tok123");

        assertTrue(user.isWhatsAppOptIn());
        assertTrue(user.isWhatsAppWeeklyReportEnabled());
        assertEquals("+5551997513229", user.getWhatsAppNumber());
        assertNotNull(user.getWhatsAppOptInAt());
        verify(userRepository).save(user);
        // no category data → text-only (no media url)
        verify(twilioClient).sendWhatsApp(eq("+5551997513229"), contains("SEU RESUMO"), isNull());
    }

    @Test
    void handleInbound_relatorio_repliesWithoutOptingIn() {
        var user = userWithToken("tok123");
        when(userRepository.findByWhatsAppLinkToken("tok123")).thenReturn(Optional.of(user));
        when(reportService.buildWeeklyReport(user)).thenReturn(new WeeklyReport("SEU RESUMO", false));

        service.handleInbound("+5551997513229", "RELATORIO-tok123");

        assertFalse(user.isWhatsAppOptIn());
        verify(twilioClient).sendWhatsApp(eq("+5551997513229"), contains("SEU RESUMO"), isNull());
    }

    @Test
    void handleInbound_relatorio_withCategoryData_attachesChartImage() {
        var user = User.builder().id(UUID.randomUUID()).email("u@economizaai.app").whatsAppLinkToken("tok123").build();
        when(userRepository.findByWhatsAppLinkToken("tok123")).thenReturn(Optional.of(user));
        when(reportService.buildWeeklyReport(user)).thenReturn(new WeeklyReport("SEU RESUMO", true));
        when(chartTokenService.mint(user.getId())).thenReturn("TOKEN");

        service.handleInbound("+5551997513229", "RELATORIO-tok123");

        verify(twilioClient).sendWhatsApp(eq("+5551997513229"), contains("SEU RESUMO"),
                eq("https://api.economizaai.app/api/v1/public/report-charts/TOKEN.png"));
    }

    @Test
    void handleInbound_mediaSendFails_fallsBackToTextOnly() {
        var user = User.builder().id(UUID.randomUUID()).email("u@economizaai.app").whatsAppLinkToken("tok123").build();
        when(userRepository.findByWhatsAppLinkToken("tok123")).thenReturn(Optional.of(user));
        when(reportService.buildWeeklyReport(user)).thenReturn(new WeeklyReport("SEU RESUMO", true));
        when(chartTokenService.mint(user.getId())).thenReturn("TOKEN");
        // media send blows up (e.g. Twilio can't fetch the image)
        doThrow(new RuntimeException("twilio media 400")).when(twilioClient)
                .sendWhatsApp(anyString(), anyString(), anyString());

        assertDoesNotThrow(() -> service.handleInbound("+5551997513229", "RELATORIO-tok123"));

        // ...and the user still gets the report as text-only
        verify(twilioClient).sendWhatsApp(eq("+5551997513229"), contains("SEU RESUMO"));
    }

    @Test
    void handleInbound_reply_goesThroughTwilioCapAndBudget_andIsLedgered() {
        var user = User.builder().id(UUID.randomUUID()).email("u@economizaai.app").whatsAppLinkToken("tok123").build();
        when(userRepository.findByWhatsAppLinkToken("tok123")).thenReturn(Optional.of(user));
        when(reportService.buildWeeklyReport(user)).thenReturn(new WeeklyReport("SEU RESUMO", false));

        service.handleInbound("+5551997513229", "RELATORIO-tok123");

        verify(paidApiGuardService).assertWithinDailyCap(user.getId(), PaidApiService.TWILIO_MESSAGE);
        verify(paidApiGuardService).assertUnderDailyBudget(PaidApiService.TWILIO_MESSAGE);
        verify(paidApiGuardService).recordSuccess(user.getId(), PaidApiService.TWILIO_MESSAGE, null, "twilio");
    }

    @Test
    void handleInbound_userOverTwilioCap_skipsTheReply() {
        var user = User.builder().id(UUID.randomUUID()).email("u@economizaai.app").whatsAppLinkToken("tok123").build();
        when(userRepository.findByWhatsAppLinkToken("tok123")).thenReturn(Optional.of(user));
        when(reportService.buildWeeklyReport(user)).thenReturn(new WeeklyReport("SEU RESUMO", false));
        doThrow(new PaidApiQuotaExceededException("TWILIO_MESSAGE"))
                .when(paidApiGuardService).assertWithinDailyCap(user.getId(), PaidApiService.TWILIO_MESSAGE);

        assertDoesNotThrow(() -> service.handleInbound("+5551997513229", "RELATORIO-tok123"));

        verify(twilioClient, never()).sendWhatsApp(anyString(), anyString(), any());
        verify(paidApiGuardService, never()).recordSuccess(any(), any(), any(), any());
    }

    @Test
    void handleInbound_twilioBudgetSpent_skipsTheReply_butKeepsTheOptIn() {
        var user = User.builder().id(UUID.randomUUID()).email("u@economizaai.app").whatsAppLinkToken("tok123").build();
        when(userRepository.findByWhatsAppLinkToken("tok123")).thenReturn(Optional.of(user));
        when(reportService.buildWeeklyReport(user)).thenReturn(new WeeklyReport("SEU RESUMO", false));
        when(messageService.translate(eq("whatsapp.optin.confirmed"), any(Locale.class))).thenReturn("Vinculado!");
        doThrow(new PaidApiBudgetExceededException())
                .when(paidApiGuardService).assertUnderDailyBudget(PaidApiService.TWILIO_MESSAGE);

        service.handleInbound("+5551997513229", "CONFIRMAR-tok123");

        assertTrue(user.isWhatsAppOptIn());
        verify(twilioClient, never()).sendWhatsApp(anyString(), anyString(), any());
    }

    @Test
    void handleInbound_failedSend_isLedgeredAsFailure() {
        var user = User.builder().id(UUID.randomUUID()).email("u@economizaai.app").whatsAppLinkToken("tok123").build();
        when(userRepository.findByWhatsAppLinkToken("tok123")).thenReturn(Optional.of(user));
        when(reportService.buildWeeklyReport(user)).thenReturn(new WeeklyReport("SEU RESUMO", false));
        doThrow(new RuntimeException("twilio 500")).when(twilioClient).sendWhatsApp(anyString(), anyString(), any());

        service.handleInbound("+5551997513229", "RELATORIO-tok123");

        verify(paidApiGuardService).recordFailure(user.getId(), PaidApiService.TWILIO_MESSAGE, null, "twilio");
    }

    @Test
    void handleInbound_unknownToken_isNoOp() {
        when(userRepository.findByWhatsAppLinkToken("nope")).thenReturn(Optional.empty());

        service.handleInbound("+5551997513229", "CONFIRMAR-nope");

        verify(userRepository, never()).save(any());
        verify(twilioClient, never()).sendWhatsApp(anyString(), anyString(), anyString());
    }

    @Test
    void handleInbound_unparseableBody_neverLooksUpToken() {
        service.handleInbound("+5551997513229", "oi tudo bem?");

        verify(userRepository, never()).findByWhatsAppLinkToken(anyString());
        verify(twilioClient, never()).sendWhatsApp(anyString(), anyString(), anyString());
    }

    @Test
    void handleInbound_swallowsSendFailure() {
        var user = userWithToken("tok123");
        when(userRepository.findByWhatsAppLinkToken("tok123")).thenReturn(Optional.of(user));
        when(reportService.buildWeeklyReport(user)).thenReturn(new WeeklyReport("SEU RESUMO", false));
        when(messageService.translate(eq("whatsapp.optin.confirmed"), any(Locale.class))).thenReturn("Vinculado!");
        doThrow(new RuntimeException("twilio 500")).when(twilioClient).sendWhatsApp(anyString(), anyString(), any());

        // the inbound handler must never throw (webhook has to return 200)
        assertDoesNotThrow(() -> service.handleInbound("+5551997513229", "CONFIRMAR-tok123"));
        assertTrue(user.isWhatsAppOptIn()); // state still persisted even though the reply failed
    }

    @Test
    void ensureLinkToken_isIdempotent() {
        var existing = userWithToken("already");
        assertEquals("already", service.ensureLinkToken(existing));
        verify(userRepository, never()).save(existing);

        var fresh = userWithToken(null);
        var generated = service.ensureLinkToken(fresh);
        assertNotNull(generated);
        assertEquals(generated, fresh.getWhatsAppLinkToken());
        assertEquals(generated, service.ensureLinkToken(fresh), "second call returns the same token");
    }

    @Test
    void waMeLink_buildsDeepLinkWithDigitsAndEncodedText() {
        assertEquals("https://wa.me/14063448189?text=CONFIRMAR-abc", service.waMeLink("CONFIRMAR", "abc"));
    }

    @Test
    void updateWeeklyReport_changesTheFreshRowNotTheStaleRequestPrincipal() {
        var principal = User.builder().id(UUID.randomUUID()).email("u@economizaai.app").build();
        var stored = User.builder().id(principal.getId()).email("u@economizaai.app")
                .whatsAppOptIn(true).whatsAppNumber("+5551997513229").whatsAppLinkToken("tok123").build();
        when(userRepository.findById(principal.getId())).thenReturn(Optional.of(stored));

        var status = service.updateWeeklyReport(principal, true);

        assertTrue(stored.isWhatsAppWeeklyReportEnabled());
        assertTrue(status.weeklyReportEnabled());
        assertTrue(status.optIn());
        assertEquals("****3229", status.maskedNumber());
        verify(userRepository).save(stored);
        verify(userRepository, never()).save(principal);
    }

    @Test
    void updateWeeklyReport_requiresTheOptIn() {
        var principal = User.builder().id(UUID.randomUUID()).email("u@economizaai.app").build();
        var stored = User.builder().id(principal.getId()).email("u@economizaai.app").build();
        when(userRepository.findById(principal.getId())).thenReturn(Optional.of(stored));

        assertThrows(WhatsAppOptInRequiredException.class, () -> service.updateWeeklyReport(principal, true));

        verify(userRepository, never()).save(any());
    }

    @Test
    void issueConfirmLink_persistsTheTokenOnFirstUseAndBuildsTheConfirmLink() {
        var managedUser = userWithToken(null);

        var link = service.issueConfirmLink(managedUser);

        assertNotNull(managedUser.getWhatsAppLinkToken());
        assertEquals("https://wa.me/14063448189?text=CONFIRMAR-" + managedUser.getWhatsAppLinkToken(), link);
        verify(userRepository).save(managedUser);
    }

    @Test
    void maskedWhatsAppNumber_isNullWithoutANumber() {
        assertNull(service.maskedWhatsAppNumber(userWithToken("tok")));
        var connected = User.builder().email("u@economizaai.app").whatsAppNumber("+5551997513229").build();
        assertEquals("****3229", service.maskedWhatsAppNumber(connected));
    }
}
