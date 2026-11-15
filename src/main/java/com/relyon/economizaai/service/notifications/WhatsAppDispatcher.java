package com.relyon.economizaai.service.notifications;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.exception.PaidApiBudgetExceededException;
import com.relyon.economizaai.exception.PaidApiQuotaExceededException;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationChannel;
import com.relyon.economizaai.model.enums.NotificationNotice;
import com.relyon.economizaai.model.enums.PaidApiService;
import com.relyon.economizaai.service.notifications.twilio.TwilioMessageClient;
import com.relyon.economizaai.service.notifications.twilio.TwilioMessageException;
import com.relyon.economizaai.service.paidapi.PaidApiGuardService;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * WhatsApp channel backed by Twilio's Messages API. A notice we start is business-initiated,
 * so it only ever goes out as the notice's Meta-approved template ({@link WhatsAppTemplateRegistry})
 * — never as freeform text, which WhatsApp rejects outside the 24h window. The recipient is
 * the number the user opted in from ({@code whatsAppNumber}), not the SMS/OTP {@code phoneNumber}.
 *
 * <p>Anything missing (Twilio sender, channel not offered to the user, no opt-in, no template
 * for the notice, Twilio limits reached) degrades gracefully: a clear "not delivered" reason,
 * never an exception.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WhatsAppDispatcher implements NotificationDispatcher {

    /** Extras key a caller may set with a ready one-line text for the template variable. */
    public static final String EXTRA_ONE_LINE_TEXT = "whatsAppLine";

    static final String REASON_TWILIO_NOT_CONFIGURED = "twilio_not_configured";
    static final String REASON_NOT_AVAILABLE = "whatsapp_not_available";
    static final String REASON_NOT_OPTED_IN = "whatsapp_not_opted_in";
    static final String REASON_TEMPLATE_NOT_CONFIGURED = "template_not_configured";
    static final String REASON_QUOTA_EXCEEDED = "twilio_quota_exceeded";
    static final String REASON_VARIABLES_UNSERIALIZABLE = "template_variables_unserializable";

    private static final int MAX_VARIABLE_LENGTH = 900;

    private final TwilioMessageClient twilioMessageClient;
    private final PaidApiGuardService paidApiGuardService;
    private final WhatsAppChannelPolicy whatsAppPolicy;
    private final WhatsAppTemplateRegistry templateRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.WHATSAPP;
    }

    @Override
    public DispatchResult dispatch(NotificationPayload payload) {
        var user = payload.user();
        if (!twilioMessageClient.isConfigured(true)) return skipped(payload, REASON_TWILIO_NOT_CONFIGURED);
        if (!whatsAppPolicy.isAvailableFor(user)) return skipped(payload, REASON_NOT_AVAILABLE);
        if (!whatsAppPolicy.isConnected(user)) return skipped(payload, REASON_NOT_OPTED_IN);
        var templateSid = templateSidFor(payload);
        if (templateSid.isEmpty()) return skipped(payload, REASON_TEMPLATE_NOT_CONFIGURED);
        if (!isWithinTwilioLimits(user)) return skipped(payload, REASON_QUOTA_EXCEEDED);
        return sendTemplate(payload, templateSid.get());
    }

    private Optional<String> templateSidFor(NotificationPayload payload) {
        return NotificationNotice.of(payload.type()).flatMap(templateRegistry::templateSidFor);
    }

    private boolean isWithinTwilioLimits(User user) {
        try {
            paidApiGuardService.assertWithinDailyCap(user.getId(), PaidApiService.TWILIO_MESSAGE);
            paidApiGuardService.assertUnderDailyBudget(PaidApiService.TWILIO_MESSAGE);
            return true;
        } catch (PaidApiQuotaExceededException | PaidApiBudgetExceededException limitReached) {
            return false;
        }
    }

    private DispatchResult sendTemplate(NotificationPayload payload, String templateSid) {
        var user = payload.user();
        String templateVariables;
        try {
            templateVariables = objectMapper.writeValueAsString(Map.of("1", oneLineText(payload)));
        } catch (JsonProcessingException unserializable) {
            return skipped(payload, REASON_VARIABLES_UNSERIALIZABLE);
        }
        try {
            twilioMessageClient.sendWhatsAppTemplate(user.getWhatsAppNumber(), templateSid, templateVariables);
            paidApiGuardService.recordSuccess(user.getId(), PaidApiService.TWILIO_MESSAGE, null, "twilio");
            log.info("notification.whatsapp.sent user={} phone={} type={}",
                    LogMasker.email(user.getEmail()), LogMasker.phone(user.getWhatsAppNumber()), payload.type());
            return DispatchResult.ok();
        } catch (TwilioMessageException sendFailure) {
            paidApiGuardService.recordFailure(user.getId(), PaidApiService.TWILIO_MESSAGE, null, "twilio");
            log.warn("notification.whatsapp.failed user={} phone={} type={} {}",
                    LogMasker.email(user.getEmail()), LogMasker.phone(user.getWhatsAppNumber()),
                    payload.type(), sendFailure.getMessage());
            return DispatchResult.failed(sendFailure.getMessage());
        }
    }

    private DispatchResult skipped(NotificationPayload payload, String reason) {
        log.info("notification.whatsapp.skipped user={} type={} reason={}",
                LogMasker.email(payload.user().getEmail()), payload.type(), reason);
        return DispatchResult.failed(reason);
    }

    /**
     * The text of the single {@code {{1}}} slot every notice template has. WhatsApp refuses
     * newlines, tabs and runs of spaces inside a template variable.
     */
    static String oneLineText(NotificationPayload payload) {
        var readyLine = payload.extras() == null ? null : payload.extras().get(EXTRA_ONE_LINE_TEXT);
        var text = readyLine != null ? readyLine.toString() : payload.body();
        var singleLine = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= MAX_VARIABLE_LENGTH ? singleLine : singleLine.substring(0, MAX_VARIABLE_LENGTH);
    }
}
