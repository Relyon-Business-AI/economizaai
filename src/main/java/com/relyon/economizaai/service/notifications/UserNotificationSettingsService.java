package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.dto.request.UpdateNotificationSettingsRequest;
import com.relyon.economizaai.dto.response.NotificationSettingsResponse;
import com.relyon.economizaai.exception.InvalidNotificationSettingsException;
import com.relyon.economizaai.exception.UserNotFoundException;
import com.relyon.economizaai.exception.WhatsAppNotConnectedException;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.DigestFrequency;
import com.relyon.economizaai.model.enums.NotificationChannel;
import com.relyon.economizaai.model.enums.NotificationNotice;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The user's notification settings screen: every notice with the channels that exist for it,
 * each one switched on or off on its own ({@link NotificationNotice}). The WhatsApp options
 * are only listed — and only accepted — for the accounts the channel is offered to
 * ({@link WhatsAppChannelPolicy}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserNotificationSettingsService {

    private final UserRepository userRepository;
    private final NoticeOptionStore optionStore;
    private final WhatsAppChannelPolicy whatsAppPolicy;
    private final WhatsAppOptInService whatsAppOptInService;

    /**
     * The settings as the screen shows them. For an account WhatsApp is offered to and that has
     * not connected yet, this also issues the wa.me confirm link — which persists the user's
     * link token the first time.
     */
    @Transactional
    public NotificationSettingsResponse openSettings(User principal) {
        return describe(reload(principal));
    }

    /** Applies only what the request carries; the whole request is validated before anything changes. */
    @Transactional
    public NotificationSettingsResponse update(User principal, UpdateNotificationSettingsRequest request) {
        var managedUser = reload(principal);
        var optionChanges = validatedOptionChanges(managedUser, request.options());
        var digestFrequency = validatedDigestFrequency(request.digestFrequency());

        optionChanges.forEach(change ->
                optionStore.saveOption(managedUser, change.notice(), change.channel(), change.enabled()));
        digestFrequency.ifPresent(frequency -> chooseDigestFrequency(managedUser, frequency));
        optionStore.alignDigestFrequencyWithOptions(managedUser);
        userRepository.save(managedUser);

        log.info("notification.settings.updated user={} options={} digestFrequency={}",
                LogMasker.email(managedUser.getEmail()), optionChanges.size(), digestFrequency.orElse(null));
        return describe(managedUser);
    }

    private record ValidOptionChange(NotificationNotice notice, NotificationChannel channel, boolean enabled) {}

    private List<ValidOptionChange> validatedOptionChanges(User user,
                                                           List<UpdateNotificationSettingsRequest.OptionChange> changes) {
        if (changes == null) return List.of();
        return changes.stream().map(change -> validatedOptionChange(user, change)).toList();
    }

    private ValidOptionChange validatedOptionChange(User user, UpdateNotificationSettingsRequest.OptionChange change) {
        if (change == null) throw InvalidNotificationSettingsException.unknownNotice(null);
        var notice = NotificationNotice.fromKey(change.key())
                .orElseThrow(() -> InvalidNotificationSettingsException.unknownNotice(change.key()));
        var channel = channelNamed(change.channel());
        if (!notice.offers(channel)) throw InvalidNotificationSettingsException.channelNotOffered();
        if (change.enabled() == null) throw InvalidNotificationSettingsException.missingEnabled();
        if (notice.isLocked(channel) && !change.enabled()) throw InvalidNotificationSettingsException.lockedOption();
        if (channel == NotificationChannel.WHATSAPP) requireWhatsAppCanBeSet(user, change.enabled());
        return new ValidOptionChange(notice, channel, change.enabled());
    }

    private static NotificationChannel channelNamed(String channelName) {
        return Arrays.stream(NotificationChannel.values())
                .filter(channel -> channel.name().equals(channelName))
                .findFirst()
                .orElseThrow(() -> InvalidNotificationSettingsException.unknownChannel(channelName));
    }

    private void requireWhatsAppCanBeSet(User user, boolean enabled) {
        if (!whatsAppPolicy.isAvailableFor(user)) throw InvalidNotificationSettingsException.whatsAppUnavailable();
        if (enabled && !whatsAppPolicy.isConnected(user)) throw new WhatsAppNotConnectedException();
    }

    private static Optional<DigestFrequency> validatedDigestFrequency(String frequencyName) {
        if (frequencyName == null) return Optional.empty();
        return Optional.of(Arrays.stream(DigestFrequency.values())
                .filter(frequency -> frequency != DigestFrequency.OFF)
                .filter(frequency -> frequency.name().equals(frequencyName))
                .findFirst()
                .orElseThrow(InvalidNotificationSettingsException::invalidDigestFrequency));
    }

    /** While the digest is off the choice is only remembered; it takes effect when an option goes back on. */
    private static void chooseDigestFrequency(User managedUser, DigestFrequency frequency) {
        managedUser.setDigestFrequencyChoice(frequency);
        if (managedUser.getDigestFrequency() != DigestFrequency.OFF) {
            managedUser.setDigestFrequency(frequency);
        }
    }

    private NotificationSettingsResponse describe(User managedUser) {
        var whatsAppAvailable = whatsAppPolicy.isAvailableFor(managedUser);
        var whatsAppConnected = whatsAppAvailable && whatsAppPolicy.isConnected(managedUser);
        var options = optionStore.optionsOf(managedUser);
        var notices = Arrays.stream(NotificationNotice.values())
                .map(notice -> describeNotice(notice, options, whatsAppAvailable, whatsAppConnected))
                .toList();
        return new NotificationSettingsResponse(
                managedUser.getEmail(),
                describeWhatsApp(managedUser, whatsAppAvailable, whatsAppConnected),
                NoticeOptionStore.chosenDigestFrequency(managedUser),
                notices);
    }

    private NotificationSettingsResponse.WhatsApp describeWhatsApp(User managedUser, boolean available,
                                                                   boolean connected) {
        if (!available) return new NotificationSettingsResponse.WhatsApp(false, false, null, null);
        if (connected) {
            return new NotificationSettingsResponse.WhatsApp(true, true,
                    whatsAppOptInService.maskedWhatsAppNumber(managedUser), null);
        }
        return new NotificationSettingsResponse.WhatsApp(true, false, null,
                whatsAppOptInService.issueConfirmLink(managedUser));
    }

    private static NotificationSettingsResponse.Notice describeNotice(NotificationNotice notice, NoticeOptions options,
                                                                      boolean whatsAppAvailable,
                                                                      boolean whatsAppConnected) {
        var noticeOptions = notice.channels().stream()
                .filter(channel -> channel != NotificationChannel.WHATSAPP || whatsAppAvailable)
                .map(channel -> new NotificationSettingsResponse.Option(
                        channel,
                        options.isEnabled(notice, channel)
                                && (channel != NotificationChannel.WHATSAPP || whatsAppConnected),
                        notice.isLocked(channel)))
                .toList();
        return new NotificationSettingsResponse.Notice(notice, noticeOptions);
    }

    // The authenticated User is a detached snapshot taken when the request started: saving it
    // would write that snapshot back over whatever changed since. Mutate a fresh, managed load.
    private User reload(User principal) {
        return userRepository.findById(principal.getId())
                .orElseThrow(() -> new UserNotFoundException(principal.getId().toString()));
    }
}
