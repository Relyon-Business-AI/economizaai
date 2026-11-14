package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.dto.request.UpdateDigestPreferencesRequest;
import com.relyon.economizaai.dto.request.UpdateNotificationPreferencesRequest;
import com.relyon.economizaai.dto.request.UpdatePushTokenRequest;
import com.relyon.economizaai.dto.response.DigestPreferencesResponse;
import com.relyon.economizaai.dto.response.NotificationPreferenceResponse;
import com.relyon.economizaai.exception.UserNotFoundException;
import com.relyon.economizaai.model.NotificationPreference;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.DigestFrequency;
import com.relyon.economizaai.model.enums.NotificationChannel;
import com.relyon.economizaai.model.enums.NotificationNotice;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.NotificationPreferenceRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationPreferenceService {

    private final NotificationPreferenceRepository preferenceRepository;
    private final UserRepository userRepository;
    private final NoticeOptionStore optionStore;

    /**
     * The one-channel-per-type view older app versions still read. Types that belong to a
     * notice are answered from the notice options ({@code PUSH} when push is on, else
     * {@code EMAIL} when e-mail is on, else {@code NONE}) — always listed, so the old screen
     * shows what really happens. The few types outside the notice model list their stored rows.
     */
    @Transactional(readOnly = true)
    public List<NotificationPreferenceResponse> list(User user) {
        var options = optionStore.optionsOf(user);
        var noticeTypes = Arrays.stream(NotificationType.values())
                .filter(NotificationPreferenceService::isKnownToOlderApps)
                .flatMap(type -> NotificationNotice.of(type).stream()
                        .map(notice -> new NotificationPreferenceResponse(type, legacyChannel(options, notice))));
        var typesOutsideTheNoticeModel = preferenceRepository.findAllByUserId(user.getId()).stream()
                .filter(preference -> !preference.getType().isRetired())
                .filter(preference -> NotificationNotice.of(preference.getType()).isEmpty())
                .map(preference -> new NotificationPreferenceResponse(preference.getType(), preference.getChannel()));
        return Stream.concat(noticeTypes, typesOutsideTheNoticeModel).toList();
    }

    /** The weekly report type did not exist when the one-channel-per-type view was designed. */
    private static boolean isKnownToOlderApps(NotificationType type) {
        return type != NotificationType.WEEKLY_REPORT;
    }

    private static NotificationChannel legacyChannel(NoticeOptions options, NotificationNotice notice) {
        if (options.isEnabled(notice, NotificationChannel.PUSH)) return NotificationChannel.PUSH;
        if (options.isEnabled(notice, NotificationChannel.EMAIL)) return NotificationChannel.EMAIL;
        return NotificationChannel.NONE;
    }

    /**
     * The per-type channel write of older app versions. The request carries the COMPLETE
     * channel set of each type it mentions; types not mentioned are untouched. A type that
     * belongs to a notice is translated to the notice options ({@link #applyLegacyChoice});
     * the others keep their rows, replaced wholesale.
     */
    @Transactional
    public List<NotificationPreferenceResponse> update(User user, UpdateNotificationPreferencesRequest request) {
        var channelsByType = request.preferences().stream()
                .filter(preference -> !preference.type().isRetired())
                .collect(Collectors.groupingBy(
                        UpdateNotificationPreferencesRequest.Preference::type,
                        LinkedHashMap::new,
                        Collectors.mapping(UpdateNotificationPreferencesRequest.Preference::channel,
                                Collectors.toCollection(LinkedHashSet::new))));
        var managedUser = reload(user);

        legacyChannelsByNotice(channelsByType)
                .forEach((notice, channels) -> applyLegacyChoice(managedUser, notice, channels));
        optionStore.alignDigestFrequencyWithOptions(managedUser);
        userRepository.save(managedUser);
        replaceRowsOfTypesOutsideTheNoticeModel(managedUser, channelsByType);

        log.info("notification.preferences.updated user={} types={}",
                LogMasker.email(managedUser.getEmail()), channelsByType.size());
        return list(managedUser);
    }

    /** Several types of one notice in the same request are read together: a channel any of them asks for is on. */
    private static Map<NotificationNotice, Set<NotificationChannel>> legacyChannelsByNotice(
            Map<NotificationType, LinkedHashSet<NotificationChannel>> channelsByType) {
        var channelsByNotice = new EnumMap<NotificationNotice, Set<NotificationChannel>>(NotificationNotice.class);
        channelsByType.forEach((type, channels) -> NotificationNotice.of(type).ifPresent(notice ->
                channelsByNotice.computeIfAbsent(notice, unused -> EnumSet.noneOf(NotificationChannel.class))
                        .addAll(channels)));
        return channelsByNotice;
    }

    /**
     * PUSH ⇒ push on, e-mail off. EMAIL ⇒ e-mail on, push off — unless the notice has no
     * e-mail channel, in which case push stays on (an old EMAIL choice must not silently
     * stop the delivery). Both ⇒ both on. NONE (or anything else) ⇒ every option off; a
     * locked option stays on.
     */
    private void applyLegacyChoice(User managedUser, NotificationNotice notice, Set<NotificationChannel> channels) {
        var pushAsked = channels.contains(NotificationChannel.PUSH);
        var emailAsked = channels.contains(NotificationChannel.EMAIL);
        if (!pushAsked && !emailAsked) {
            optionStore.saveAllOptionsOff(managedUser, notice);
            return;
        }
        var emailOn = emailAsked && notice.offers(NotificationChannel.EMAIL);
        optionStore.saveOption(managedUser, notice, NotificationChannel.PUSH, pushAsked || !emailOn);
        optionStore.saveOption(managedUser, notice, NotificationChannel.EMAIL, emailOn);
    }

    private void replaceRowsOfTypesOutsideTheNoticeModel(
            User managedUser, Map<NotificationType, LinkedHashSet<NotificationChannel>> channelsByType) {
        var typesOutsideTheNoticeModel = channelsByType.keySet().stream()
                .filter(type -> NotificationNotice.of(type).isEmpty())
                .toList();
        typesOutsideTheNoticeModel.forEach(type -> preferenceRepository.deleteByUserIdAndType(managedUser.getId(), type));
        preferenceRepository.flush(); // ensure deletes land before re-inserts (unique constraint)
        typesOutsideTheNoticeModel.forEach(type -> channelsByType.get(type).forEach(channel ->
                preferenceRepository.save(NotificationPreference.builder()
                        .user(managedUser).type(type).channel(channel).build())));
    }

    /** Older app versions read OFF while every DEALS_DIGEST option is off. */
    @Transactional(readOnly = true)
    public DigestPreferencesResponse getDigestPreferences(User user) {
        return new DigestPreferencesResponse(user.getDigestFrequency(), user.getDigestSendHour());
    }

    /**
     * Older app versions send OFF to stop the digest: that switches every DEALS_DIGEST option
     * off (the DAILY/WEEKLY choice is remembered). DAILY/WEEKLY set the frequency and, when
     * no option is on, switch the push option back on.
     */
    @Transactional
    public DigestPreferencesResponse updateDigestPreferences(User user, UpdateDigestPreferencesRequest request) {
        var managedUser = reload(user);
        if (request.frequency() == DigestFrequency.OFF) {
            optionStore.saveAllOptionsOff(managedUser, NotificationNotice.DEALS_DIGEST);
        } else {
            managedUser.setDigestFrequency(request.frequency());
            managedUser.setDigestFrequencyChoice(request.frequency());
            if (!optionStore.hasAnyOptionOn(managedUser, NotificationNotice.DEALS_DIGEST)) {
                optionStore.saveOption(managedUser, NotificationNotice.DEALS_DIGEST, NotificationChannel.PUSH, true);
            }
        }
        optionStore.alignDigestFrequencyWithOptions(managedUser);
        managedUser.setDigestSendHour(request.sendHour());
        // An explicit hour is the effective one; clearing it lets the digest job pick again.
        managedUser.setDigestEffectiveSendHour(request.sendHour());
        userRepository.save(managedUser);
        log.info("digest.preferences.updated user={} frequency={} sendHour={}",
                LogMasker.email(managedUser.getEmail()), request.frequency(), request.sendHour());
        return new DigestPreferencesResponse(managedUser.getDigestFrequency(), managedUser.getDigestSendHour());
    }

    @Transactional
    public void updatePushToken(User user, UpdatePushTokenRequest request) {
        var token = request.pushDeviceToken();
        var managedUser = reload(user);
        managedUser.setPushDeviceToken(token == null || token.isBlank() ? null : token);
        managedUser.setPushTokenUpdatedAt(LocalDateTime.now());
        userRepository.save(managedUser);
        log.info("push_token.updated user={} hasToken={}",
                LogMasker.email(managedUser.getEmail()), managedUser.getPushDeviceToken() != null);
    }

    // The authenticated User is a detached snapshot taken when the request started: saving it
    // would write that snapshot back over whatever changed since. Mutate a fresh, managed load.
    private User reload(User principal) {
        return userRepository.findById(principal.getId())
                .orElseThrow(() -> new UserNotFoundException(principal.getId().toString()));
    }
}
