package com.relyon.economizaai.service.auth;

import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.Platform;
import com.relyon.economizaai.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LoginActivityRecorderTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private LoginActivityRecorder loginActivityRecorder;

    @Test
    void recordLogin_nullPlatform_isNoOp() {
        var user = new User();

        loginActivityRecorder.recordLogin(user, null);

        assertNull(user.getLastPlatform());
        verify(userRepository, never()).save(any());
    }

    @Test
    void recordLogin_stampsLastPlatformAndMatchingTimestampOnly() {
        var user = new User();

        loginActivityRecorder.recordLogin(user, Platform.ANDROID);

        assertEquals(Platform.ANDROID, user.getLastPlatform());
        assertNotNull(user.getLastAndroidLoginAt());
        assertNull(user.getLastWebLoginAt());
        assertNull(user.getLastIosLoginAt());
        assertNull(user.getRegistrationPlatform());
        verify(userRepository).save(user);
    }

    @Test
    void recordRegistration_setsRegistrationAndLastPlatform() {
        var user = new User();

        loginActivityRecorder.recordRegistration(user, Platform.WEB);

        assertEquals(Platform.WEB, user.getRegistrationPlatform());
        assertEquals(Platform.WEB, user.getLastPlatform());
        assertNotNull(user.getLastWebLoginAt());
        verify(userRepository).save(user);
    }

    @Test
    void recordRegistration_nullPlatform_isNoOp() {
        var user = new User();

        loginActivityRecorder.recordRegistration(user, null);

        assertNull(user.getRegistrationPlatform());
        verify(userRepository, never()).save(any());
    }
}
