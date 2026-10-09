package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.NotificationAudience;
import com.relyon.economizaai.model.NotificationCampaign;
import com.relyon.economizaai.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.Month;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class NotificationCampaignRepositoryTest {

    @Autowired private NotificationCampaignRepository campaignRepository;
    @Autowired private NotificationAudienceRepository audienceRepository;
    @Autowired private HouseholdRepository householdRepository;
    @Autowired private UserRepository userRepository;

    private User createUser(String suffix) {
        var household = householdRepository.save(Household.builder().inviteCode("CMP" + suffix).build());
        return userRepository.save(User.builder()
                .name("User " + suffix).email("campaign" + suffix + "@test.com").password("x")
                .household(household)
                .acceptedTermsVersion("1.0").acceptedPrivacyVersion("1.0")
                .acceptedLegalAt(LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0))
                .build());
    }

    /**
     * Regressão do bug de listagem: campanhas de lista explícita têm
     * audience NULL e sumiam da página porque o fetch era INNER JOIN.
     */
    @Test
    void listIncludesCampaignsWithoutAudience() {
        var audience = audienceRepository.save(NotificationAudience.builder().name("Admins CMP").build());
        var recipient = createUser("R1");

        var audienceCampaign = campaignRepository.save(NotificationCampaign.builder()
                .name("com publico").title("t").body("b").audience(audience).build());
        var explicitCampaign = NotificationCampaign.builder()
                .name("lista explicita").title("t").body("b").build();
        explicitCampaign.getRecipientUserIds().add(recipient.getId());
        explicitCampaign = campaignRepository.save(explicitCampaign);

        var page = campaignRepository.findAllWithAudience(PageRequest.of(0, 10));

        var ids = page.getContent().stream().map(NotificationCampaign::getId).toList();
        assertTrue(ids.contains(audienceCampaign.getId()), "audience campaign listed");
        assertTrue(ids.contains(explicitCampaign.getId()), "explicit-recipients campaign listed");
        assertEquals(2, page.getTotalElements());
    }
}
