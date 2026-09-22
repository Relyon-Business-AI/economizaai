package com.relyon.economizaai.service;

import com.relyon.economizaai.dto.request.UpdateContributionRequest;
import com.relyon.economizaai.dto.request.UpdateHomeLocationRequest;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.model.enums.SubscriptionTier;
import com.relyon.economizaai.repository.HouseholdRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.auth.EmailVerificationService;
import com.relyon.economizaai.service.auth.RefreshTokenService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceCoverageTest {

    @Mock private UserRepository userRepository;
    @Mock private HouseholdRepository householdRepository;
    @Mock private ReceiptRepository receiptRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;
    @Mock private HouseholdService householdService;
    @Mock private EmailVerificationService emailVerificationService;
    @Mock private RefreshTokenService refreshTokenService;

    @InjectMocks private UserService userService;

    private User buildUser() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        var user = User.builder()
                .id(UUID.randomUUID())
                .name("John")
                .email("john@test.com")
                .password("encoded")
                .role(Role.USER)
                .subscriptionTier(SubscriptionTier.FREE)
                .contributionOptIn(true)
                .active(true)
                .household(household)
                .build();
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        return user;
    }

    @Test
    void updateHomeLocation_setsCoordinatesAndTimestamp() {
        var user = buildUser();
        var request = new UpdateHomeLocationRequest(new BigDecimal("-30.0277"), new BigDecimal("-51.2287"));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = userService.updateHomeLocation(user, request);

        assertEquals(0, response.homeLatitude().compareTo(new BigDecimal("-30.0277")));
        assertEquals(0, response.homeLongitude().compareTo(new BigDecimal("-51.2287")));
        assertEquals(0, user.getHomeLatitude().compareTo(new BigDecimal("-30.0277")));
        assertEquals(0, user.getHomeLongitude().compareTo(new BigDecimal("-51.2287")));
        assertNotNull(user.getHomeSetAt());
        verify(userRepository).save(user);
    }

    @Test
    void updateContribution_enablesOptIn() {
        var user = buildUser();
        user.setContributionOptIn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = userService.updateContribution(user, new UpdateContributionRequest(true));

        assertEquals(true, response.contributionOptIn());
        assertEquals(true, user.isContributionOptIn());
    }

    @Test
    void exportData_throwsWhenHouseholdMissing() {
        var user = buildUser();
        when(householdRepository.findById(user.getHousehold().getId())).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> userService.exportData(user));
    }
}
