package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.enums.DigestFrequency;
import com.relyon.economizaai.model.enums.NotificationChannel;
import com.relyon.economizaai.model.enums.NotificationNotice;
import com.relyon.economizaai.model.enums.SpendingReportFrequency;

import java.util.List;

/**
 * The notification settings screen: every notice with the channels that exist for it, each
 * one switched on or off independently. {@code email} is the address e-mail notices go to.
 * {@code digestFrequency} is always DAILY or WEEKLY — it keeps the last choice while every
 * DEALS_DIGEST option is off. {@code reportFrequencies} are the periods the spending report
 * (the WEEKLY_REPORT notice) covers: WEEKLY and/or MONTHLY, in that order, never empty.
 */
public record NotificationSettingsResponse(
        String email,
        WhatsApp whatsApp,
        DigestFrequency digestFrequency,
        List<SpendingReportFrequency> reportFrequencies,
        List<Notice> notices
) {

    /**
     * WhatsApp is only offered to some accounts: when {@code available} is false,
     * {@code connected} is false, the other fields are null and no notice lists a WHATSAPP
     * option. {@code confirmLink} is the wa.me link that connects the user's WhatsApp —
     * present only while available and not yet connected.
     */
    public record WhatsApp(
            boolean available,
            boolean connected,
            String maskedNumber,
            String confirmLink
    ) {}

    public record Notice(NotificationNotice key, List<Option> options) {}

    public record Option(NotificationChannel channel, boolean enabled) {}
}
