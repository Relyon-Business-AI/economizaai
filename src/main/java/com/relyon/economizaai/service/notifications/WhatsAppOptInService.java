package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.dto.response.WhatsAppStatusResponse;
import com.relyon.economizaai.exception.WhatsAppOptInRequiredException;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.notifications.twilio.TwilioMessageClient;
import com.relyon.economizaai.time.BrazilClock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * User-facing WhatsApp opt-in via the reverse flow: the app shows a wa.me deep-link
 * carrying a per-user token; the user messages our WhatsApp with "CONFIRMAR-<token>"
 * (or "RELATORIO-<token>" to pull on demand); the inbound webhook calls
 * {@link #handleInbound}, which links the number + replies IN-WINDOW (freeform, free).
 * Proactive weekly sends (a paid template) live in the scheduler, gated OFF.
 */
@Slf4j
@Service
public class WhatsAppOptInService {

    private static final Pattern INBOUND = Pattern.compile("^(CONFIRMAR|RELATORIO)-(\\S+)$", Pattern.CASE_INSENSITIVE);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final TwilioMessageClient twilioClient;
    private final WhatsAppReportService reportService;
    private final ReportChartTokenService chartTokenService;
    private final LocalizedMessageService messageService;
    private final TransactionTemplate transactionTemplate;
    private final String fromWhatsAppDigits;
    private final String publicBaseUrl;

    public WhatsAppOptInService(UserRepository userRepository,
                                TwilioMessageClient twilioClient,
                                WhatsAppReportService reportService,
                                ReportChartTokenService chartTokenService,
                                LocalizedMessageService messageService,
                                TransactionTemplate transactionTemplate,
                                @Value("${economizaai.notifications.twilio.from-whatsapp:}") String fromWhatsApp,
                                @Value("${economizaai.public-base-url:https://api.economizaai.app}") String publicBaseUrl) {
        this.userRepository = userRepository;
        this.twilioClient = twilioClient;
        this.reportService = reportService;
        this.chartTokenService = chartTokenService;
        this.messageService = messageService;
        this.transactionTemplate = transactionTemplate;
        this.fromWhatsAppDigits = fromWhatsApp == null ? "" : fromWhatsApp.replaceAll("\\D", "");
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.replaceAll("/+$", "");
    }

    /** Current opt-in status + the deep-links the FE renders (generates the token on first read). */
    @Transactional
    public WhatsAppStatusResponse status(User user) {
        var token = ensureLinkToken(user);
        return new WhatsAppStatusResponse(
                user.isWhatsAppOptIn(),
                user.isWhatsAppWeeklyReportEnabled(),
                maskNumber(user.getWhatsAppNumber()),
                waMeLink("CONFIRMAR", token),
                waMeLink("RELATORIO", token));
    }

    /** Flips the weekly-report toggle. Requires a prior opt-in (linked WhatsApp). */
    @Transactional
    public WhatsAppStatusResponse updateWeeklyReport(User user, boolean enabled) {
        if (!user.isWhatsAppOptIn()) {
            throw new WhatsAppOptInRequiredException();
        }
        user.setWhatsAppWeeklyReportEnabled(enabled);
        userRepository.save(user);
        return status(user);
    }

    /** Ensures the user has a stable deep-link token; generates + persists one on first use. */
    @Transactional
    public String ensureLinkToken(User user) {
        if (user.getWhatsAppLinkToken() == null || user.getWhatsAppLinkToken().isBlank()) {
            user.setWhatsAppLinkToken(generateToken());
            userRepository.save(user);
        }
        return user.getWhatsAppLinkToken();
    }

    /** Twilio inbound webhook entry: parse "CONFIRMAR|RELATORIO-<token>", link + reply (never throws).
     *  The DB work (resolve + opt-in + build report text) runs in a short tx; the Twilio reply (HTTP)
     *  happens OUTSIDE it — we never hold a connection across the outbound call (CLAUDE.md). */
    public void handleInbound(String fromE164, String body) {
        if (body == null) return;
        var matcher = INBOUND.matcher(body.trim());
        if (!matcher.matches()) {
            log.info("whatsapp.inbound.unmatched from={}", maskNumber(fromE164));
            return;
        }
        var action = matcher.group(1).toUpperCase();
        var token = matcher.group(2);
        var reply = transactionTemplate.execute(txStatus -> prepareReply(action, token, fromE164));
        if (reply != null) {
            reply(fromE164, reply);
        }
    }

    /** The reply body plus the user whose chart to attach (null = no chart, text-only). */
    private record PreparedReply(String body, UUID chartUserId) {}

    /** Runs inside the tx: resolves the user by token, applies opt-in state, and builds the reply
     *  (DB reads). Returns the prepared reply, or null when no user matches the token. */
    private PreparedReply prepareReply(String action, String token, String fromE164) {
        var user = userRepository.findByWhatsAppLinkToken(token).orElse(null);
        if (user == null) {
            log.info("whatsapp.inbound.unmatched_token from={}", maskNumber(fromE164));
            return null;
        }
        if ("CONFIRMAR".equals(action)) {
            user.setWhatsAppNumber(fromE164);
            user.setWhatsAppOptIn(true);
            user.setWhatsAppOptInAt(BrazilClock.nowDateTime());
            user.setWhatsAppWeeklyReportEnabled(true);
            userRepository.save(user);
            log.info("whatsapp.optin.confirmed user={} from={}", abbrev(user), maskNumber(fromE164));
            var report = reportService.buildWeeklyReport(user);
            var body = messageService.translate("whatsapp.optin.confirmed", localeOf(user)) + "\n\n" + report.text();
            return new PreparedReply(body, report.hasCategoryData() ? user.getId() : null);
        }
        if (user.getWhatsAppNumber() == null) {
            user.setWhatsAppNumber(fromE164);
            userRepository.save(user);
        }
        log.info("whatsapp.report.pull user={} from={}", abbrev(user), maskNumber(fromE164));
        var report = reportService.buildWeeklyReport(user);
        return new PreparedReply(report.text(), report.hasCategoryData() ? user.getId() : null);
    }

    private Locale localeOf(User user) {
        var locale = user.getLocale();
        return Locale.forLanguageTag(locale == null || locale.isBlank() ? "pt" : locale);
    }

    public String waMeLink(String action, String token) {
        var text = URLEncoder.encode(action + "-" + token, StandardCharsets.UTF_8);
        return "https://wa.me/" + fromWhatsAppDigits + "?text=" + text;
    }

    /** Sends the reply OUTSIDE the tx. When the report has category data, attaches the pie chart as
     *  an image + caption in ONE message (same cost as text) via a short-lived public chart URL. */
    private void reply(String toE164, PreparedReply prepared) {
        try {
            var mediaUrl = prepared.chartUserId() == null ? null : chartUrl(prepared.chartUserId());
            twilioClient.sendWhatsApp(toE164, prepared.body(), mediaUrl);
        } catch (RuntimeException sendFailure) {
            log.warn("whatsapp.inbound.reply_failed to={} reason={}", maskNumber(toE164), sendFailure.getClass().getSimpleName());
        }
    }

    /** Public, token-gated URL Twilio fetches for the category pie (regenerated on fetch). */
    private String chartUrl(UUID userId) {
        return publicBaseUrl + "/api/v1/public/report-charts/" + chartTokenService.mint(userId) + ".png";
    }

    private static String generateToken() {
        var bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String maskNumber(String phone) {
        return phone == null || phone.length() < 4 ? "****" : "****" + phone.substring(phone.length() - 4);
    }

    private static String abbrev(User user) {
        return user.getId() == null ? "?" : user.getId().toString().substring(0, 8);
    }
}
