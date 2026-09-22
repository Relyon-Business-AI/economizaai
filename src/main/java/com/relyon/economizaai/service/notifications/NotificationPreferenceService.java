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
import java.util.List;

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
        for (var entry : request.preferences()) {
            var existing = preferenceRepository.findByUserIdAndType(user.getId(), entry.type());
            var preference = existing.orElseGet(() -> NotificationPreference.builder()
                    .user(user).type(entry.type()).build());
            preference.setChannel(entry.channel());
            preferenceRepository.save(preference);
        }
        log.info("notification.preferences.updated user={} count={}", LogMasker.email(user.getEmail()), request.preferences().size());
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
