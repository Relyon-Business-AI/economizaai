package com.relyon.economizaai.service.admin;

import com.relyon.economizaai.dto.request.SaveAudienceRequest;
import com.relyon.economizaai.exception.AudienceInUseException;
import com.relyon.economizaai.exception.AudienceNameConflictException;
import com.relyon.economizaai.exception.BuiltInAudienceException;
import com.relyon.economizaai.exception.NotificationAudienceNotFoundException;
import com.relyon.economizaai.model.NotificationAudience;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.model.enums.SubscriptionTier;
import com.relyon.economizaai.repository.NotificationAudienceRepository;
import com.relyon.economizaai.repository.NotificationCampaignRepository;
import com.relyon.economizaai.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAudienceServiceTest {

    @Mock private NotificationAudienceRepository audienceRepository;
    @Mock private NotificationCampaignRepository campaignRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private AdminAudienceService service;

    private static NotificationAudience adminsAudience(boolean builtIn) {
        return NotificationAudience.builder()
                .id(UUID.randomUUID()).name("Admins").builtIn(builtIn).role(Role.ADMIN)
                .build();
    }

    private static SaveAudienceRequest request(String name) {
        return new SaveAudienceRequest(name, "desc", Role.USER, SubscriptionTier.PRO,
                "pt", true, 30, 14);
    }

    @Test
    void createRejectsDuplicateName() {
        when(audienceRepository.existsByNameIgnoreCase("Admins")).thenReturn(true);
        assertThrows(AudienceNameConflictException.class, () -> service.create(request("Admins")));
        verify(audienceRepository, never()).save(any());
    }

    @Test
    void createMapsEveryFilterAndReturnsLiveMatchCount() {
        when(audienceRepository.existsByNameIgnoreCase("PRO engajados")).thenReturn(false);
        when(audienceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.count(any(Specification.class))).thenReturn(7L);

        var response = service.create(request("PRO engajados"));

        assertEquals("PRO engajados", response.name());
        assertEquals(Role.USER, response.role());
        assertEquals(SubscriptionTier.PRO, response.subscriptionTier());
        assertEquals("pt", response.locale());
        assertEquals(true, response.hasPushToken());
        assertEquals(30, response.registeredWithinDays());
        assertEquals(14, response.activeWithinDays());
        assertEquals(7L, response.matchCount());
    }

    @Test
    void updateRejectsBuiltInAudience() {
        var builtIn = adminsAudience(true);
        when(audienceRepository.findById(builtIn.getId())).thenReturn(Optional.of(builtIn));
        assertThrows(BuiltInAudienceException.class,
                () -> service.update(builtIn.getId(), request("Renamed")));
    }

    @Test
    void deleteRejectsBuiltInAudience() {
        var builtIn = adminsAudience(true);
        when(audienceRepository.findById(builtIn.getId())).thenReturn(Optional.of(builtIn));
        assertThrows(BuiltInAudienceException.class, () -> service.delete(builtIn.getId()));
    }

    @Test
    void deleteRejectsAudienceReferencedByCampaigns() {
        var audience = adminsAudience(false);
        when(audienceRepository.findById(audience.getId())).thenReturn(Optional.of(audience));
        when(campaignRepository.existsByAudienceId(audience.getId())).thenReturn(true);
        assertThrows(AudienceInUseException.class, () -> service.delete(audience.getId()));
        verify(audienceRepository, never()).delete(any(NotificationAudience.class));
    }

    @Test
    void deleteRemovesUnusedCustomAudience() {
        var audience = adminsAudience(false);
        when(audienceRepository.findById(audience.getId())).thenReturn(Optional.of(audience));
        when(campaignRepository.existsByAudienceId(audience.getId())).thenReturn(false);
        service.delete(audience.getId());
        verify(audienceRepository).delete(audience);
    }

    @Test
    void previewReturnsCountAndSampleEmails() {
        var audience = adminsAudience(true);
        when(audienceRepository.findById(audience.getId())).thenReturn(Optional.of(audience));
        var admin = User.builder().id(UUID.randomUUID()).email("alexandre@economizaai.app").build();
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(admin)));
        when(userRepository.count(any(Specification.class))).thenReturn(1L);

        var preview = service.preview(audience.getId());

        assertEquals(1L, preview.matchCount());
        assertEquals(List.of("alexandre@economizaai.app"), preview.sampleEmails());
    }

    @Test
    void getThrowsWhenAudienceMissing() {
        var missingId = UUID.randomUUID();
        when(audienceRepository.findById(missingId)).thenReturn(Optional.empty());
        assertThrows(NotificationAudienceNotFoundException.class, () -> service.get(missingId));
    }

    @Test
    void listOrdersBuiltInFirstWithCounts() {
        var builtIn = adminsAudience(true);
        var custom = adminsAudience(false);
        custom.setName("Custom");
        when(audienceRepository.findAllByOrderByBuiltInDescNameAsc()).thenReturn(List.of(builtIn, custom));
        when(userRepository.count(any(Specification.class))).thenReturn(2L);

        var audiences = service.list();

        assertEquals(2, audiences.size());
        assertTrue(audiences.get(0).builtIn());
        assertEquals(2L, audiences.get(0).matchCount());
    }
}
