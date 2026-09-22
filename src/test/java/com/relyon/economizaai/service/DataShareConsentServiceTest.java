package com.relyon.economizaai.service;

import com.relyon.economizaai.exception.InvalidConsentRequestException;
import com.relyon.economizaai.model.DataShareConsent;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ConsentStatus;
import com.relyon.economizaai.model.enums.LeaveScope;
import com.relyon.economizaai.repository.DataShareConsentRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.notifications.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataShareConsentServiceTest {

    @Mock private DataShareConsentRepository consentRepository;
    @Mock private UserRepository userRepository;
    @Mock private HouseholdMergeService mergeService;
    @Mock private NotificationService notificationService;

    @InjectMocks private DataShareConsentService consentService;

    private User user(String name) {
        return User.builder().id(UUID.randomUUID()).name(name).email(name + "@test.com").build();
    }

    private Household household() {
        return Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
    }

    private DataShareConsent pending(User requester, User grantor, Household shared, Household dest) {
        return DataShareConsent.builder()
                .requester(requester).grantor(grantor)
                .household(shared).destinationHousehold(dest)
                .scope(LeaveScope.BOTH).status(ConsentStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusDays(14)).build();
    }

    @Test
    void request_persistsPendingAndNotifiesGrantor() {
        var requester = user("ana");
        var grantor = user("bob");
        var shared = household();
        var dest = household();
        when(consentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var consent = consentService.request(requester, grantor, shared, dest, LeaveScope.BOTH);

        assertEquals(ConsentStatus.PENDING, consent.getStatus());
        assertEquals(grantor, consent.getGrantor());
        verify(notificationService).notify(any());
    }

    @Test
    void request_rejectsSelfRequest() {
        var u = user("ana");
        var h = household();

        assertThrows(InvalidConsentRequestException.class,
                () -> consentService.request(u, u, h, h, LeaveScope.BOTH));
        verify(consentRepository, never()).save(any());
    }

    @Test
    void approve_copiesGrantorDataAndNotifiesRequester() {
        var requester = user("ana");
        var grantor = user("bob");
        var shared = household();
        var dest = household();
        var consent = pending(requester, grantor, shared, dest);
        when(consentRepository.findByIdAndGrantorId(any(), any())).thenReturn(Optional.of(consent));
        when(mergeService.copyUserData(grantor.getId(), shared, dest)).thenReturn(3);

        var result = consentService.approve(grantor, UUID.randomUUID());

        assertEquals(ConsentStatus.APPROVED, result.getStatus());
        verify(mergeService).copyUserData(grantor.getId(), shared, dest);
        verify(notificationService).notify(any());
    }

    @Test
    void approve_rejectsAlreadyResolved() {
        var consent = pending(user("ana"), user("bob"), household(), household());
        consent.setStatus(ConsentStatus.DENIED);
        when(consentRepository.findByIdAndGrantorId(any(), any())).thenReturn(Optional.of(consent));

        assertThrows(InvalidConsentRequestException.class,
                () -> consentService.approve(user("bob"), UUID.randomUUID()));
        verify(mergeService, never()).copyUserData(any(), any(), any());
    }

    @Test
    void approve_rejectsAndExpiresWhenPastTtl() {
        var consent = pending(user("ana"), user("bob"), household(), household());
        consent.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(consentRepository.findByIdAndGrantorId(any(), any())).thenReturn(Optional.of(consent));

        assertThrows(InvalidConsentRequestException.class,
                () -> consentService.approve(user("bob"), UUID.randomUUID()));
        assertEquals(ConsentStatus.EXPIRED, consent.getStatus());
        verify(mergeService, never()).copyUserData(any(), any(), any());
    }

    @Test
    void approve_rejectsWhenNotFoundForGrantor() {
        when(consentRepository.findByIdAndGrantorId(any(), any())).thenReturn(Optional.empty());

        assertThrows(InvalidConsentRequestException.class,
                () -> consentService.approve(user("bob"), UUID.randomUUID()));
    }

    @Test
    void deny_marksDeniedAndCopiesNothing() {
        var consent = pending(user("ana"), user("bob"), household(), household());
        when(consentRepository.findByIdAndGrantorId(any(), any())).thenReturn(Optional.of(consent));

        var result = consentService.deny(user("bob"), UUID.randomUUID());

        assertEquals(ConsentStatus.DENIED, result.getStatus());
        verify(mergeService, never()).copyUserData(any(), any(), any());
        verify(notificationService).notify(any());
    }
}
