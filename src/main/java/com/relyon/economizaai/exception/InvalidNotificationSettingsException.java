package com.relyon.economizaai.exception;

/** A notification-settings change that can never be applied (unknown notice or channel, bad value). */
public class InvalidNotificationSettingsException extends DomainException {

    private InvalidNotificationSettingsException(String messageKey, String... arguments) {
        super(messageKey, arguments);
    }

    public static InvalidNotificationSettingsException unknownNotice(String key) {
        return new InvalidNotificationSettingsException("notification.settings.notice.unknown", String.valueOf(key));
    }

    public static InvalidNotificationSettingsException unknownChannel(String channel) {
        return new InvalidNotificationSettingsException("notification.settings.channel.unknown", String.valueOf(channel));
    }

    public static InvalidNotificationSettingsException channelNotOffered() {
        return new InvalidNotificationSettingsException("notification.settings.channel.not-offered");
    }

    public static InvalidNotificationSettingsException missingEnabled() {
        return new InvalidNotificationSettingsException("notification.settings.option.enabled-required");
    }

    public static InvalidNotificationSettingsException invalidDigestFrequency() {
        return new InvalidNotificationSettingsException("notification.settings.digest-frequency.invalid");
    }

    public static InvalidNotificationSettingsException invalidReportFrequencies() {
        return new InvalidNotificationSettingsException("notification.settings.report-frequencies.invalid");
    }

    public static InvalidNotificationSettingsException whatsAppUnavailable() {
        return new InvalidNotificationSettingsException("notification.settings.whatsapp.unavailable");
    }
}
