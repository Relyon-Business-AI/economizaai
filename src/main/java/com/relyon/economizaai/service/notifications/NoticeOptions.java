package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.model.NotificationNoticeOption;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationChannel;
import com.relyon.economizaai.model.enums.NotificationNotice;

import java.util.List;

/**
 * The switches one user has stored, read as "is this channel of this notice on?". An option
 * the user never touched follows the notice's default (push on, e-mail and WhatsApp off, the
 * locked account e-mail on). A locked option is on whatever is stored.
 *
 * <p>The WhatsApp option of the weekly report is the one switch kept on the {@link User} row
 * ({@code whatsAppWeeklyReportEnabled}) — the WhatsApp opt-in flow and the older
 * {@code /users/me/whatsapp} endpoint already write it there.
 */
public final class NoticeOptions {

    private final User user;
    private final List<NotificationNoticeOption> storedOptions;

    public NoticeOptions(User user, List<NotificationNoticeOption> storedOptions) {
        this.user = user;
        this.storedOptions = List.copyOf(storedOptions);
    }

    public boolean isEnabled(NotificationNotice notice, NotificationChannel channel) {
        if (!notice.offers(channel)) return false;
        if (notice.isLocked(channel)) return true;
        if (isKeptOnTheUserRow(notice, channel)) return user.isWhatsAppWeeklyReportEnabled();
        return storedOptions.stream()
                .filter(option -> option.getNotice() == notice && option.getChannel() == channel)
                .findFirst()
                .map(NotificationNoticeOption::isEnabled)
                .orElseGet(() -> notice.isOnByDefault(channel));
    }

    /** True once the user (or the migration of an older choice) stored any option of the notice. */
    public boolean isConfigured(NotificationNotice notice) {
        return storedOptions.stream().anyMatch(option -> option.getNotice() == notice);
    }

    static boolean isKeptOnTheUserRow(NotificationNotice notice, NotificationChannel channel) {
        return notice == NotificationNotice.WEEKLY_REPORT && channel == NotificationChannel.WHATSAPP;
    }
}
