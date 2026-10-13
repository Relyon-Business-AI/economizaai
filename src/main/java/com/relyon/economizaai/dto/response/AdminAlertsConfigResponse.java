package com.relyon.economizaai.dto.response;

/**
 * Snapshot of the operational-alert channels for the admin "Alertas" screen.
 *
 * @param emailActive              e-mail is always attempted (the always-on channel)
 * @param whatsAppEnabled          master switch for the WhatsApp channel
 * @param adminPhoneCount          how many admin numbers would receive a WhatsApp alert
 * @param adminTemplateConfigured  the admin template SID is set
 * @param twilioWhatsAppConfigured Twilio creds + WhatsApp From number are set
 * @param templateStatus           live Meta approval status of the admin template
 *                                 ({@code approved}, {@code pending}, {@code rejected},
 *                                 {@code unknown}, {@code unset})
 */
public record AdminAlertsConfigResponse(
        boolean emailActive,
        boolean whatsAppEnabled,
        int adminPhoneCount,
        boolean adminTemplateConfigured,
        boolean twilioWhatsAppConfigured,
        String templateStatus) {
}
