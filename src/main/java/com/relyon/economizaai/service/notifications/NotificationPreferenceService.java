package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.dto.request.UpdateDigestPreferencesRequest;
import com.relyon.economizaai.dto.request.UpdateNotificationPreferencesRequest;
import com.relyon.economizaai.dto.request.UpdatePushTokenRequest;
import com.relyon.economizaai.dto.response.DigestPreferencesResponse;
import com.relyon.economizaai.dto.response.NotificationPreferenceResponse;
import com.relyon.economizaai.model.NotificationPreference;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.NotificationPreferenceRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationPreferenceService {

    private final NotificationPreferenceRepository preferenceRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<NotificationPreferenceResponse> list(User user) {
        return preferenceRepository.findAllByUserId(user.getId()).stream()
                .map(p -> new NotificationPreferenceResponse(p.getType(), p.getChannel()))
                .toList();
    }

    @Transactional
    public List<NotificationPreferenceResponse> update(User user, UpdateNotificationPreferencesRequest request) {
        // The request carries the COMPLETE desired set of channels for each type it
        // mentions (multi-channel). Replace that type's rows wholesale; types not in
        // the request are left untouched.
        var channelsByType = request.preferences().stream()
                .collect(Collectors.groupingBy(
                        UpdateNotificationPreferencesRequest.Preference::type,
                        LinkedHashMap::new,
                        Collectors.mapping(UpdateNotificationPreferencesRequest.Preference::channel,
                                Collectors.toCollection(LinkedHashSet::new))));

        channelsByType.keySet().forEach(type -> preferenceRepository.deleteByUserIdAndType(user.getId(), type));
        preferenceRepository.flush(); // ensure deletes land before re-inserts (unique constraint)
        channelsByType.forEach((type, channels) -> channels.forEach(channel ->
                preferenceRepository.save(NotificationPreference.builder()
                        .user(user).type(type).channel(channel).build())));

        log.info("notification.preferences.updated user={} types={}",
                LogMasker.email(user.getEmail()), channelsByType.size());
        return list(user);
    }

    @Transactional(readOnly = true)
    public DigestPreferencesResponse getDigestPreferences(User user) {
        return new DigestPreferencesResponse(user.getDigestFrequency(), user.getDigestSendHour());
    }

    @Transactional
    public DigestPreferencesResponse updateDigestPreferences(User user, UpdateDigestPreferencesRequest request) {
        user.setDigestFrequency(request.frequency());
        user.setDigestSendHour(request.sendHour());
        userRepository.save(user);
        log.info("digest.preferences.updated user={} frequency={} sendHour={}",
                LogMasker.email(user.getEmail()), request.frequency(), request.sendHour());
        return new DigestPreferencesResponse(user.getDigestFrequency(), user.getDigestSendHour());
    }

    @Transactional
    public void updatePushToken(User user, UpdatePushTokenRequest request) {
        var token = request.pushDeviceToken();
        user.setPushDeviceToken(token == null || token.isBlank() ? null : token);
        user.setPushTokenUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        log.info("push_token.updated user={} hasToken={}", LogMasker.email(user.getEmail()), user.getPushDeviceToken() != null);
    }
}
