package com.relyon.economizaai.controller;

import com.relyon.economizaai.service.notifications.WhatsAppOptInService;
import com.relyon.economizaai.service.notifications.twilio.TwilioSignatureVerifier;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Receives Twilio inbound WhatsApp messages (the reverse opt-in "CONFIRMAR-<token>" /
 * on-demand "RELATORIO-<token>"). Validates the X-Twilio-Signature; an invalid signature
 * is logged and ignored (still 200, so Twilio doesn't retry a spoofed/misconfigured call).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
@Tag(name = "Webhooks", description = "Provider callbacks (public, signature-verified)")
public class TwilioWhatsAppWebhookController {

    private static final String PATH = "/api/v1/webhooks/twilio/whatsapp-inbound";
    private static final String WHATSAPP_PREFIX = "whatsapp:";

    private final TwilioSignatureVerifier signatureVerifier;
    private final WhatsAppOptInService whatsAppOptInService;

    @Value("${economizaai.public-base-url:https://api.economizaai.app}")
    private String publicBaseUrl;

    @PostMapping(value = "/twilio/whatsapp-inbound", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> inbound(@RequestParam Map<String, String> params,
                                        @RequestHeader(value = "X-Twilio-Signature", required = false) String signature) {
        var url = publicBaseUrl + PATH;
        if (!signatureVerifier.isValid(url, params, signature)) {
            log.warn("twilio.whatsapp.inbound.bad_signature");
            return ResponseEntity.ok().build();
        }
        var from = stripPrefix(params.get("From"));
        whatsAppOptInService.handleInbound(from, params.get("Body"));
        return ResponseEntity.ok().build();
    }

    private static String stripPrefix(String value) {
        if (value == null) return null;
        return value.startsWith(WHATSAPP_PREFIX) ? value.substring(WHATSAPP_PREFIX.length()) : value;
    }
}
