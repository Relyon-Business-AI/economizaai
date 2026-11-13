package com.relyon.economizaai.service.auth;

import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.AcquisitionChannel;
import com.relyon.economizaai.model.enums.Platform;
import com.relyon.economizaai.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
        verify(userRepository).save(user);
    }

    @Test
    void recordLogin_backfillsRegistrationPlatformWhenMissing() {
        var user = new User();

        loginActivityRecorder.recordLogin(user, Platform.ANDROID);

        assertEquals(Platform.ANDROID, user.getRegistrationPlatform());
    }

    @Test
    void recordLogin_neverOverwritesKnownRegistrationPlatform() {
        var user = new User();
        user.setRegistrationPlatform(Platform.IOS);

        loginActivityRecorder.recordLogin(user, Platform.WEB);

        assertEquals(Platform.IOS, user.getRegistrationPlatform());
        assertEquals(Platform.WEB, user.getLastPlatform());
    }

    @Test
    void recordRegistration_unattributedAppSignupIsAttributedToItsStore() {
        var iosUser = new User();
        iosUser.setAcquisitionChannel(AcquisitionChannel.UNKNOWN);
        var androidUser = new User();
        androidUser.setAcquisitionChannel(AcquisitionChannel.UNKNOWN);
        var webUser = new User();
        webUser.setAcquisitionChannel(AcquisitionChannel.UNKNOWN);

        loginActivityRecorder.recordRegistration(iosUser, Platform.IOS);
        loginActivityRecorder.recordRegistration(androidUser, Platform.ANDROID);
        loginActivityRecorder.recordRegistration(webUser, Platform.WEB);

        assertEquals(AcquisitionChannel.APP_STORE, iosUser.getAcquisitionChannel());
        assertEquals(AcquisitionChannel.GOOGLE_PLAY, androidUser.getAcquisitionChannel());
        assertEquals(AcquisitionChannel.UNKNOWN, webUser.getAcquisitionChannel());
    }

    @Test
    void recordRegistration_neverOverridesRealWebAttribution() {
        var user = new User();
        user.setAcquisitionChannel(AcquisitionChannel.INSTAGRAM_PAID);

        loginActivityRecorder.recordRegistration(user, Platform.IOS);

        assertEquals(AcquisitionChannel.INSTAGRAM_PAID, user.getAcquisitionChannel());
    }

    @Test
    void recordLogin_backfilledPlatformAlsoAttributesTheStore() {
        var user = new User();

        loginActivityRecorder.recordLogin(user, Platform.ANDROID);

        assertEquals(AcquisitionChannel.GOOGLE_PLAY, user.getAcquisitionChannel());
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
    void recordSessionRenewal_fillsAnUnknownRegistrationPlatformWithoutStampingALogin() {
        var user = new User();
        var userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        loginActivityRecorder.recordSessionRenewal(userId, Platform.IOS);

        assertEquals(Platform.IOS, user.getRegistrationPlatform());
        assertEquals(Platform.IOS, user.getLastPlatform());
        assertEquals(AcquisitionChannel.APP_STORE, user.getAcquisitionChannel());
        assertNull(user.getLastIosLoginAt()); // a renewal is not a login
        verify(userRepository).save(user);
    }

    @Test
    void recordSessionRenewal_neverOverwritesKnownRegistrationPlatform() {
        var user = new User();
        user.setRegistrationPlatform(Platform.ANDROID);
        user.setLastPlatform(Platform.ANDROID);
        var userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        loginActivityRecorder.recordSessionRenewal(userId, Platform.WEB);

        assertEquals(Platform.ANDROID, user.getRegistrationPlatform());
        assertEquals(Platform.WEB, user.getLastPlatform());
        verify(userRepository).save(user);
    }

    @Test
    void recordSessionRenewal_nothingNewToRecord_doesNotWrite() {
        var user = new User();
        user.setRegistrationPlatform(Platform.ANDROID);
        user.setLastPlatform(Platform.ANDROID);
        var userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        loginActivityRecorder.recordSessionRenewal(userId, Platform.ANDROID);

        verify(userRepository, never()).save(any());
    }

    @Test
    void recordSessionRenewal_nullPlatform_doesNotEvenLoadTheUser() {
        loginActivityRecorder.recordSessionRenewal(UUID.randomUUID(), null);

        verifyNoInteractions(userRepository);
    }

    @Test
    void recordSessionRenewal_aFailureNeverReachesTheCaller() {
        var userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenThrow(new IllegalStateException("db down"));

        assertDoesNotThrow(() -> loginActivityRecorder.recordSessionRenewal(userId, Platform.IOS));
    }

    @Test
    void recordRegistration_nullPlatform_isNoOp() {
        var user = new User();

        loginActivityRecorder.recordRegistration(user, null);

        assertNull(user.getRegistrationPlatform());
        verify(userRepository, never()).save(any());
    }
}
