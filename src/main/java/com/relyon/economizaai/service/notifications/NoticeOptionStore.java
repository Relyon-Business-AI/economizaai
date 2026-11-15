package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.model.NotificationNoticeOption;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.DigestFrequency;
import com.relyon.economizaai.model.enums.NotificationChannel;
import com.relyon.economizaai.model.enums.NotificationNotice;
import com.relyon.economizaai.repository.NotificationNoticeOptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Reads and writes the per-notice, per-channel switches. Writes take a MANAGED user (reloaded
 * by the caller, inside its transaction) because two pieces live on the user row: the
 * WhatsApp option of the weekly report and the digest frequency, which is kept in step with
 * the DEALS_DIGEST options ({@code OFF} exactly while none of them is on).
 */
@Component
@RequiredArgsConstructor
public class NoticeOptionStore {

    private final NotificationNoticeOptionRepository optionRepository;
    private final WhatsAppChannelPolicy whatsAppPolicy;

    public NoticeOptions optionsOf(User user) {
        return new NoticeOptions(user, optionRepository.findAllByUserId(user.getId()));
    }

    public NoticeOptions optionsOf(User user, NotificationNotice notice) {
        return new NoticeOptions(user, optionRepository.findAllByUserIdAndNotice(user.getId(), notice));
    }

    /** Stores one switch. A channel the notice does not offer is left alone. */
    public void saveOption(User managedUser, NotificationNotice notice, NotificationChannel channel, boolean enabled) {
        if (!notice.offers(channel)) return;
        if (NoticeOptions.isKeptOnTheUserRow(notice, channel)) {
            managedUser.setWhatsAppWeeklyReportEnabled(enabled);
            return;
        }
        var option = optionRepository.findAllByUserIdAndNotice(managedUser.getId(), notice).stream()
                .filter(stored -> stored.getChannel() == channel)
                .findFirst()
                .orElseGet(() -> NotificationNoticeOption.builder()
                        .user(managedUser).notice(notice).channel(channel).build());
        option.setEnabled(enabled);
        optionRepository.save(option);
    }

    /** Switches every option of the notice off. */
    public void saveAllOptionsOff(User managedUser, NotificationNotice notice) {
        notice.channels().forEach(channel -> saveOption(managedUser, notice, channel, false));
    }

    /**
     * Brings the digest frequency on the user row in line with the DEALS_DIGEST options: with
     * none on it becomes {@code OFF} (the digest job skips the user) and the DAILY/WEEKLY
     * choice is remembered; with any on, a frequency that was {@code OFF} goes back to it.
     */
    public void alignDigestFrequencyWithOptions(User managedUser) {
        var digestIsOn = hasAnyOptionOn(managedUser, NotificationNotice.DEALS_DIGEST);
        var storedFrequency = managedUser.getDigestFrequency();
        if (!digestIsOn && storedFrequency != DigestFrequency.OFF) {
            managedUser.setDigestFrequencyChoice(storedFrequency);
            managedUser.setDigestFrequency(DigestFrequency.OFF);
        } else if (digestIsOn && storedFrequency == DigestFrequency.OFF) {
            managedUser.setDigestFrequency(chosenDigestFrequency(managedUser));
        }
    }

    /** True while at least one channel the user can actually use is on for the notice. */
    public boolean hasAnyOptionOn(User user, NotificationNotice notice) {
        var options = optionsOf(user, notice);
        return notice.channels().stream()
                .filter(channel -> channel != NotificationChannel.WHATSAPP || whatsAppPolicy.isUsableBy(user))
                .anyMatch(channel -> options.isEnabled(notice, channel));
    }

    /** The frequency the digest runs at — or would run at once an option is switched back on. */
    public static DigestFrequency chosenDigestFrequency(User user) {
        if (user.getDigestFrequency() != null && user.getDigestFrequency() != DigestFrequency.OFF) {
            return user.getDigestFrequency();
        }
        return user.getDigestFrequencyChoice() != null && user.getDigestFrequencyChoice() != DigestFrequency.OFF
                ? user.getDigestFrequencyChoice()
                : DigestFrequency.DAILY;
    }
}
