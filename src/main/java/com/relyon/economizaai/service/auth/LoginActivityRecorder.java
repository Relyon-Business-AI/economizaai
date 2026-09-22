package com.relyon.economizaai.service.auth;

import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.Platform;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * Persists the client platform (web / android / ios) a user authenticates from.
 * The platform is optional metadata forwarded by the FE, so a null value is a
 * no-op — login/registration never fails because it was absent or unrecognized.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginActivityRecorder {

    private final UserRepository userRepository;

    /** Stamp the last-login platform and per-platform login time. No-op when platform is null. */
    public void recordLogin(User user, Platform platform) {
        if (platform == null) {
            return;
        }
        stampLogin(user, platform);
        userRepository.save(user);
        log.info("login.platform_recorded user={} platform={}", LogMasker.email(user.getEmail()), platform);
    }

    /** Set the immutable registration platform, then stamp it as the first login. No-op when platform is null. */
    public void recordRegistration(User user, Platform platform) {
        if (platform == null) {
            return;
        }
        user.setRegistrationPlatform(platform);
        recordLogin(user, platform);
    }

    private void stampLogin(User user, Platform platform) {
        user.setLastPlatform(platform);
        var now = OffsetDateTime.now();
        switch (platform) {
            case WEB -> user.setLastWebLoginAt(now);
            case ANDROID -> user.setLastAndroidLoginAt(now);
            case IOS -> user.setLastIosLoginAt(now);
        }
    }
}
