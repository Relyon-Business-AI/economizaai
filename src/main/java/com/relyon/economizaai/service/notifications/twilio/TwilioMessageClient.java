package com.relyon.economizaai.service.notifications.twilio;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Thin wrapper over the Twilio REST Messages API — does BOTH SMS and WhatsApp
 * (WhatsApp is just the same endpoint with {@code whatsapp:}-prefixed From/To).
 *
 * <p>Env-gated and off by default: {@link #isConfigured(boolean)} is true only
 * when accountSid + authToken + the relevant From number are all set. When
 * unconfigured the dispatchers degrade gracefully (record a "twilio_not_configured"
 * failure) rather than calling this client.
 *
 * <p>This client is the swap seam — replacing Twilio with another SMS/WhatsApp
 * provider means re-implementing only this class.
 */
@Slf4j
@Component
public class TwilioMessageClient {

    private static final String API_BASE = "https://api.twilio.com/2010-04-01/Accounts/";
    private static final String WHATSAPP_PREFIX = "whatsapp:";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;

    private final RestClient restClient;
    private final String accountSid;
    private final String authToken;
    private final String fromSms;
    private final String fromWhatsApp;

    public TwilioMessageClient(RestClient.Builder builder,
                               @Value("${economizaai.notifications.twilio.account-sid:}") String accountSid,
                               @Value("${economizaai.notifications.twilio.auth-token:}") String authToken,
                               @Value("${economizaai.notifications.twilio.from-sms:}") String fromSms,
                               @Value("${economizaai.notifications.twilio.from-whatsapp:}") String fromWhatsApp) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        requestFactory.setReadTimeout(READ_TIMEOUT_MS);
        this.restClient = builder.requestFactory(requestFactory).build();
        this.accountSid = accountSid;
        this.authToken = authToken;
        this.fromSms = fromSms;
        this.fromWhatsApp = fromWhatsApp;
    }

    /**
     * True when credentials AND the From number for the requested transport are set.
     * @param whatsApp true to check the WhatsApp From number, false for the SMS one.
     */
    public boolean isConfigured(boolean whatsApp) {
        if (isBlank(accountSid) || isBlank(authToken)) return false;
        return whatsApp ? !isBlank(fromWhatsApp) : !isBlank(fromSms);
    }

    /** Sends an SMS via Twilio. Throws {@link TwilioMessageException} on HTTP/transport error. */
    public void sendSms(String toE164, String body) {
        send(fromSms, toE164, body);
    }

    /** Sends a WhatsApp message via Twilio (From/To get the {@code whatsapp:} prefix). */
    public void sendWhatsApp(String toE164, String body) {
        send(WHATSAPP_PREFIX + fromWhatsApp, WHATSAPP_PREFIX + toE164, body);
    }

    /** Raw POST — isolated as a seam so callers/tests can stub the HTTP exchange. */
    protected void post(String from, String to, String body) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("From", from);
        form.add("To", to);
        form.add("Body", body);
        restClient.post()
                .uri(API_BASE + accountSid + "/Messages.json")
                .header("Authorization", basicAuthHeader())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body((MultiValueMap<String, String>) form)
                .retrieve()
                .toBodilessEntity();
    }

    private void send(String from, String to, String body) {
        try {
            post(from, to, body);
        } catch (Exception ex) {
            throw new TwilioMessageException(
                    "Twilio send failed: " + ex.getClass().getSimpleName() + ": " + ex.getMessage(), ex);
        }
    }

    private String basicAuthHeader() {
        var credentials = accountSid + ":" + authToken;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
