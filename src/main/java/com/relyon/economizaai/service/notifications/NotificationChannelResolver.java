package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationChannel;
import com.relyon.economizaai.model.enums.NotificationNotice;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.NotificationPreferenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * Decides where one notification goes out. A type that belongs to a
 * {@link NotificationNotice} leaves through every channel of the notice the user has on;
 * with none on it is muted (no inbox row, no dispatch). The few types outside the notice
 * model keep the per-type channel rows ({@code notification_preferences}) they always had.
 * An inbox-only type never goes out, whatever is stored: it is only ever the inbox row.
 *
 * <p>One rule from before the notice model is kept as it was: a user who never configured
 * the notice and has no push token gets it by e-mail instead of the push that cannot reach
 * them.
 */
@Component
@RequiredArgsConstructor
public class NotificationChannelResolver {

    private static final Set<NotificationChannel> LEGACY_OUTBOUND =
            EnumSet.of(NotificationChannel.PUSH, NotificationChannel.EMAIL);

    private final NoticeOptionStore optionStore;
    private final NotificationPreferenceRepository preferenceRepository;
    private final WhatsAppChannelPolicy whatsAppPolicy;

    /** {@code muted}: no inbox row and no dispatch. Otherwise the outbound channels (may be empty = inbox only). */
    public record Resolution(boolean muted, Set<NotificationChannel> outbound) {

        static Resolution silenced() {
            return new Resolution(true, EnumSet.noneOf(NotificationChannel.class));
        }

        static Resolution goingOutOn(Set<NotificationChannel> outbound) {
            return new Resolution(false, outbound);
        }
    }

    public Resolution resolve(User user, NotificationType type) {
        if (type.isInboxOnly()) return Resolution.goingOutOn(EnumSet.noneOf(NotificationChannel.class));
        return NotificationNotice.of(type)
                .map(notice -> resolveNotice(user, notice))
                .orElseGet(() -> resolveTypeOutsideTheNoticeModel(user, type));
    }

    private Resolution resolveNotice(User user, NotificationNotice notice) {
        var options = optionStore.optionsOf(user, notice);
        var switchedOn = EnumSet.noneOf(NotificationChannel.class);
        notice.channels().stream()
                .filter(channel -> options.isEnabled(notice, channel))
                .filter(channel -> channel != NotificationChannel.WHATSAPP || whatsAppPolicy.isUsableBy(user))
                .forEach(switchedOn::add);
        if (switchedOn.isEmpty()) return Resolution.silenced();

        var pushCannotReachAnUnconfiguredUser = switchedOn.contains(NotificationChannel.PUSH)
                && !hasPushToken(user) && !options.isConfigured(notice);
        if (pushCannotReachAnUnconfiguredUser) {
            switchedOn.remove(NotificationChannel.PUSH);
            switchedOn.add(NotificationChannel.EMAIL);
        }
        return Resolution.goingOutOn(switchedOn);
    }

    /** Previous behaviour, untouched: NONE mutes, no row = push when there is a token, else e-mail. */
    private Resolution resolveTypeOutsideTheNoticeModel(User user, NotificationType type) {
        var configured = EnumSet.noneOf(NotificationChannel.class);
        preferenceRepository.findAllByUserIdAndType(user.getId(), type)
                .forEach(preference -> configured.add(preference.getChannel()));
        if (configured.contains(NotificationChannel.NONE)) return Resolution.silenced();
        if (configured.isEmpty()) {
            return Resolution.goingOutOn(
                    EnumSet.of(hasPushToken(user) ? NotificationChannel.PUSH : NotificationChannel.EMAIL));
        }
        configured.retainAll(LEGACY_OUTBOUND);
        return Resolution.goingOutOn(configured);
    }

    private static boolean hasPushToken(User user) {
        return user.getPushDeviceToken() != null && !user.getPushDeviceToken().isBlank();
    }
}
