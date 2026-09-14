package com.relyon.economizai.service;

import com.relyon.economizai.dto.request.ChangePasswordRequest;
import com.relyon.economizai.dto.request.LoginRequest;
import com.relyon.economizai.dto.request.RegisterRequest;
import com.relyon.economizai.dto.request.UpdateContributionRequest;
import com.relyon.economizai.dto.request.UpdateUserRequest;
import com.relyon.economizai.exception.EmailAlreadyExistsException;
import com.relyon.economizai.exception.InvalidCredentialsException;
import com.relyon.economizai.exception.SocialAccountLoginException;
import com.relyon.economizai.exception.InvalidCurrentPasswordException;
import com.relyon.economizai.exception.InvalidLegalVersionException;
import com.relyon.economizai.model.Household;
import com.relyon.economizai.model.HouseholdCustomCategory;
import com.relyon.economizai.model.HouseholdMarketAlias;
import com.relyon.economizai.model.HouseholdProductCategoryOverride;
import com.relyon.economizai.model.ManualPurchase;
import com.relyon.economizai.model.Product;
import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.model.ShoppingList;
import com.relyon.economizai.model.Subscription;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.UserWatchedMarket;
import com.relyon.economizai.model.enums.ProductCategory;
import com.relyon.economizai.model.enums.AuthProvider;
import com.relyon.economizai.model.enums.Role;
import com.relyon.economizai.model.enums.SubscriptionStatus;
import com.relyon.economizai.model.enums.SubscriptionTier;
import com.relyon.economizai.repository.HouseholdCustomCategoryRepository;
import com.relyon.economizai.repository.HouseholdMarketAliasRepository;
import com.relyon.economizai.repository.HouseholdProductCategoryOverrideRepository;
import com.relyon.economizai.repository.HouseholdRepository;
import com.relyon.economizai.repository.ManualPurchaseRepository;
import com.relyon.economizai.repository.NotificationRepository;
import com.relyon.economizai.repository.NotificationPreferenceRepository;
import com.relyon.economizai.repository.NotificationEventRepository;
import com.relyon.economizai.repository.ConsumptionSnoozeRepository;
import com.relyon.economizai.repository.ManualBrandPreferenceRepository;
import com.relyon.economizai.repository.ProductRecentViewRepository;
import com.relyon.economizai.repository.DealSurfaceStateRepository;
import com.relyon.economizai.repository.HouseholdProductAliasRepository;
import com.relyon.economizai.repository.DataShareConsentRepository;
import com.relyon.economizai.repository.NotificationRuleRepository;
import com.relyon.economizai.repository.ReceiptRepository;
import com.relyon.economizai.repository.ShoppingListRepository;
import com.relyon.economizai.repository.SubscriptionRepository;
import com.relyon.economizai.service.subscription.SubscriptionService;
import com.relyon.economizai.repository.UserRepository;
import com.relyon.economizai.repository.UserWatchedMarketRepository;
import com.relyon.economizai.security.JwtService;
import com.relyon.economizai.model.enums.Platform;
import com.relyon.economizai.service.auth.EmailVerificationService;
import com.relyon.economizai.service.auth.LoginActivityRecorder;
import com.relyon.economizai.service.auth.RefreshTokenService;
import com.relyon.economizai.service.attribution.AttributionResolver;
import com.relyon.economizai.service.auth.SignupAlertService;
import com.relyon.economizai.service.notifications.NotificationRuleService;
import org.mockito.ArgumentMatchers;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private HouseholdRepository householdRepository;

    @Mock
    private ReceiptRepository receiptRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @Mock
    private HouseholdService householdService;

    @Mock
    private EmailVerificationService emailVerificationService;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private NotificationRuleService notificationRuleService;

    @Mock
    private NotificationRuleRepository notificationRuleRepository;

    @Mock
    private UserWatchedMarketRepository userWatchedMarketRepository;

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @Mock
    private HouseholdMarketAliasRepository householdMarketAliasRepository;

    @Mock
    private HouseholdCustomCategoryRepository householdCustomCategoryRepository;

    @Mock
    private HouseholdProductCategoryOverrideRepository householdProductCategoryOverrideRepository;

    @Mock
    private ManualPurchaseRepository manualPurchaseRepository;

    @Mock
    private ShoppingListRepository shoppingListRepository;

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private NotificationPreferenceRepository notificationPreferenceRepository;

    @Mock
    private NotificationEventRepository notificationEventRepository;

    @Mock
    private ConsumptionSnoozeRepository consumptionSnoozeRepository;

    @Mock
    private ManualBrandPreferenceRepository manualBrandPreferenceRepository;

    @Mock
    private ProductRecentViewRepository productRecentViewRepository;

    @Mock
    private DealSurfaceStateRepository dealSurfaceStateRepository;

    @Mock
    private HouseholdProductAliasRepository householdProductAliasRepository;

    @Mock
    private DataShareConsentRepository dataShareConsentRepository;

    @Mock
    private LoginActivityRecorder loginActivityRecorder;

    @Mock
    private SubscriptionService subscriptionService;

    @Mock
    private SignupAlertService signupAlertService;

    @Mock
    private AttributionResolver attributionResolver;

    @InjectMocks
    private UserService userService;

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
                .acceptedTermsVersion("1.0")
                .acceptedPrivacyVersion("1.0")
                .acceptedLegalAt(LocalDateTime.now())
                .build();
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        return user;
    }

    @Test
    void register_shouldCreateUserAndReturnToken() {
        var request = new RegisterRequest("John", "john@test.com", "password123", "1.0", "1.0", null);
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        when(userRepository.existsByEmail("john@test.com")).thenReturn(false);
        when(householdService.createSoloHousehold()).thenReturn(household);
        when(passwordEncoder.encode("password123")).thenReturn("encoded");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            var user = inv.<User>getArgument(0);
            user.setId(UUID.randomUUID());
            user.setCreatedAt(LocalDateTime.now());
            user.setUpdatedAt(LocalDateTime.now());
            return user;
        });
        when(jwtService.generateToken(any(User.class))).thenReturn("jwt-token");

        var response = userService.register(request);

        assertNotNull(response);
        assertEquals("jwt-token", response.token());
        assertEquals("John", response.user().name());
        assertEquals("john@test.com", response.user().email());
        assertEquals(SubscriptionTier.FREE, response.user().subscriptionTier());
        assertEquals(false, response.signupPromoGranted());
        assertEquals(null, response.signupPromoValidUntil());
        verify(userRepository).save(any(User.class));
        verify(subscriptionService).grantSignupPromoIfEnabled(any(User.class));
    }

    @Test
    void register_reflectsSignupPromoGrantInResponse() {
        var request = new RegisterRequest("John", "john@test.com", "password123", "1.0", "1.0", null);
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        when(userRepository.existsByEmail("john@test.com")).thenReturn(false);
        when(householdService.createSoloHousehold()).thenReturn(household);
        when(passwordEncoder.encode("password123")).thenReturn("encoded");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtService.generateToken(any(User.class))).thenReturn("jwt-token");
        var promoValidUntil = LocalDateTime.now().plusMonths(3);
        when(subscriptionService.grantSignupPromoIfEnabled(any(User.class))).thenReturn(promoValidUntil);

        var response = userService.register(request);

        assertEquals(true, response.signupPromoGranted());
        assertEquals(promoValidUntil, response.signupPromoValidUntil());
    }

    @Test
    void register_shouldThrowWhenEmailExists() {
        var request = new RegisterRequest("John", "john@test.com", "password123", "1.0", "1.0", null);
        when(userRepository.existsByEmail("john@test.com")).thenReturn(true);

        assertThrows(EmailAlreadyExistsException.class, () -> userService.register(request));
        verify(userRepository, never()).save(any());
    }

    @Test
    void login_shouldReturnTokenForValidCredentials() {
        var request = new LoginRequest("john@test.com", "password123", null);
        var user = buildUser();
        when(userRepository.findByEmail("john@test.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "encoded")).thenReturn(true);
        when(jwtService.generateToken(user)).thenReturn("jwt-token");

        var response = userService.login(request);

        assertNotNull(response);
        assertEquals("jwt-token", response.token());
        assertEquals("john@test.com", response.user().email());
    }

    @Test
    void login_recordsClientPlatform() {
        var request = new LoginRequest("john@test.com", "password123", Platform.ANDROID);
        var user = buildUser();
        when(userRepository.findByEmail("john@test.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "encoded")).thenReturn(true);
        when(jwtService.generateToken(user)).thenReturn("jwt-token");

        userService.login(request);

        verify(loginActivityRecorder).recordLogin(user, Platform.ANDROID);
    }

    @Test
    void register_recordsRegistrationPlatform() {
        var request = new RegisterRequest("John", "john@test.com", "password123", "1.0", "1.0", Platform.IOS);
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        when(userRepository.existsByEmail("john@test.com")).thenReturn(false);
        when(householdService.createSoloHousehold()).thenReturn(household);
        when(passwordEncoder.encode("password123")).thenReturn("encoded");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtService.generateToken(any(User.class))).thenReturn("jwt-token");

        userService.register(request);

        verify(loginActivityRecorder).recordRegistration(any(User.class), eq(Platform.IOS));
    }

    @Test
    void login_shouldThrowForInvalidPassword() {
        var request = new LoginRequest("john@test.com", "wrong", null);
        var user = buildUser();
        when(userRepository.findByEmail("john@test.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "encoded")).thenReturn(false);

        assertThrows(InvalidCredentialsException.class, () -> userService.login(request));
    }

    @Test
    void login_shouldThrowForNonExistentEmail() {
        var request = new LoginRequest("noone@test.com", "password", null);
        when(userRepository.findByEmail("noone@test.com")).thenReturn(Optional.empty());

        assertThrows(InvalidCredentialsException.class, () -> userService.login(request));
    }

    @Test
    void login_socialAccount_throwsSocialAccountLoginExceptionWithProvider() {
        var request = new LoginRequest("jane@test.com", "whatever", null);
        var socialUser = User.builder()
                .id(UUID.randomUUID())
                .name("Jane")
                .email("jane@test.com")
                .password(null)                       // social users have no local password
                .authProvider(AuthProvider.GOOGLE)
                .role(Role.USER)
                .subscriptionTier(SubscriptionTier.FREE)
                .build();
        when(userRepository.findByEmail("jane@test.com")).thenReturn(Optional.of(socialUser));

        var thrown = assertThrows(SocialAccountLoginException.class, () -> userService.login(request));

        assertEquals(AuthProvider.GOOGLE, thrown.getProvider());
        assertEquals("auth.social_account", thrown.getMessageKey());
        // must short-circuit BEFORE the password check
        verify(passwordEncoder, never()).matches(anyString(), any());
    }

    @Test
    void getProfile_shouldReturnUserResponse() {
        var user = buildUser();

        var response = userService.getProfile(user);

        assertEquals(user.getName(), response.name());
        assertEquals(user.getEmail(), response.email());
        assertEquals(SubscriptionTier.FREE, response.subscriptionTier());
    }

    @Test
    void updateProfile_shouldUpdateName() {
        var user = buildUser();
        var request = new UpdateUserRequest("New Name");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = userService.updateProfile(user, request);

        assertEquals("New Name", response.name());
        verify(userRepository).save(user);
    }

    @Test
    void changePassword_shouldUpdatePassword() {
        var user = buildUser();
        var request = new ChangePasswordRequest("currentPass", "newPassword123");
        when(passwordEncoder.matches("currentPass", user.getPassword())).thenReturn(true);
        when(passwordEncoder.encode("newPassword123")).thenReturn("newEncoded");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        userService.changePassword(user, request);

        assertEquals("newEncoded", user.getPassword());
        verify(userRepository).save(user);
    }

    @Test
    void changePassword_shouldThrowForWrongCurrentPassword() {
        var user = buildUser();
        var request = new ChangePasswordRequest("wrongPass", "newPassword123");
        when(passwordEncoder.matches("wrongPass", user.getPassword())).thenReturn(false);

        assertThrows(InvalidCurrentPasswordException.class, () -> userService.changePassword(user, request));
        verify(userRepository, never()).save(any());
    }

    @Test
    void register_shouldRejectStaleTermsVersion() {
        var request = new RegisterRequest("John", "john@test.com", "password123", "0.9", "1.0", null);
        when(userRepository.existsByEmail("john@test.com")).thenReturn(false);

        assertThrows(InvalidLegalVersionException.class,
                () -> userService.register(request));
        verify(userRepository, never()).save(any());
    }

    @Test
    void register_shouldRejectStalePrivacyVersion() {
        var request = new RegisterRequest("John", "john@test.com", "password123", "1.0", "0.9", null);
        when(userRepository.existsByEmail("john@test.com")).thenReturn(false);

        assertThrows(InvalidLegalVersionException.class,
                () -> userService.register(request));
        verify(userRepository, never()).save(any());
    }

    @Test
    void updateContribution_togglesOptInFlag() {
        var user = buildUser();
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = userService.updateContribution(user, new UpdateContributionRequest(false));

        assertEquals(false, response.contributionOptIn());
        assertEquals(false, user.isContributionOptIn());
    }

    @Test
    void deleteAccount_removesUserAndEmptyHousehold() {
        var user = buildUser();
        when(userRepository.countByHouseholdId(user.getHousehold().getId())).thenReturn(0L);

        userService.deleteAccount(user);

        verify(userRepository).delete(user);
        verify(householdRepository).deleteById(user.getHousehold().getId());
    }

    @Test
    void deleteAccount_keepsHouseholdWithRemainingMembers() {
        var user = buildUser();
        when(userRepository.countByHouseholdId(user.getHousehold().getId())).thenReturn(2L);

        userService.deleteAccount(user);

        verify(userRepository).delete(user);
        verify(householdRepository, never()).deleteById(any());
    }

    @Test
    void exportData_returnsAllPersonalDataSectionsScopedToUser() {
        var user = buildUser();
        var householdId = user.getHousehold().getId();
        var userId = user.getId();
        var product = Product.builder().id(UUID.randomUUID()).normalizedName("ARROZ").build();

        var watched = UserWatchedMarket.builder().marketCnpj("12345678000199").build();
        var subscription = Subscription.builder()
                .provider("stripe").status(SubscriptionStatus.ACTIVE)
                .currentPeriodEnd(LocalDateTime.now().plusDays(30)).build();
        var alias = HouseholdMarketAlias.builder()
                .marketCnpj("12345678000199").customName("Mercado da esquina").build();
        var customCategory = HouseholdCustomCategory.builder()
                .id(UUID.randomUUID()).name("FRUTAS").build();
        var override = HouseholdProductCategoryOverride.builder()
                .product(product).category(ProductCategory.GROCERIES).build();
        var manualPurchase = ManualPurchase.builder()
                .product(product).quantity(new BigDecimal("2.000"))
                .purchasedAt(LocalDateTime.now()).build();
        var shoppingList = ShoppingList.builder()
                .id(UUID.randomUUID()).name("Compras do mes").build();

        when(householdRepository.findById(householdId)).thenReturn(Optional.of(user.getHousehold()));
        when(userRepository.findAllByHouseholdId(householdId)).thenReturn(List.of(user));
        when(receiptRepository.findAll(ArgumentMatchers.<Specification<Receipt>>any())).thenReturn(List.of());
        when(notificationRuleRepository.findAllByUserId(userId)).thenReturn(List.of());
        when(userWatchedMarketRepository.findAllByUserId(userId)).thenReturn(List.of(watched));
        when(subscriptionRepository.findByUserId(userId)).thenReturn(Optional.of(subscription));
        when(householdMarketAliasRepository.findAllByHouseholdId(householdId)).thenReturn(List.of(alias));
        when(householdCustomCategoryRepository.findByHouseholdIdOrderByName(householdId)).thenReturn(List.of(customCategory));
        when(householdProductCategoryOverrideRepository.findAllByHouseholdId(householdId)).thenReturn(List.of(override));
        when(manualPurchaseRepository.findAllByHouseholdId(householdId)).thenReturn(List.of(manualPurchase));
        when(shoppingListRepository.findAllByHouseholdIdOrderByCreatedAtDesc(householdId)).thenReturn(List.of(shoppingList));
        when(notificationRepository.findAllByUserIdOrderByCreatedAtDesc(eq(userId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        var response = userService.exportData(user);

        assertNotNull(response.user());
        assertNotNull(response.household());
        assertEquals(0, response.receipts().size());
        assertNotNull(response.accountExtras());
        assertEquals(user.isContributionOptIn(), response.accountExtras().contributionOptIn());
        assertEquals(List.of("12345678000199"), response.watchedMarketCnpjs());
        assertEquals("stripe", response.subscription().provider());
        assertEquals(1, response.marketAliases().size());
        assertEquals("Mercado da esquina", response.marketAliases().get(0).customName());
        assertEquals(1, response.customCategories().size());
        assertEquals("FRUTAS", response.customCategories().get(0).name());
        assertEquals(1, response.categoryOverrides().size());
        assertEquals(product.getId(), response.categoryOverrides().get(0).productId());
        assertEquals("GROCERIES", response.categoryOverrides().get(0).effectiveLabel());
        assertEquals(1, response.manualPurchases().size());
        assertEquals(product.getId(), response.manualPurchases().get(0).productId());
        assertEquals(1, response.shoppingLists().size());
        assertEquals("Compras do mes", response.shoppingLists().get(0).name());
        assertNotNull(response.exportedAt());
    }

    @Test
    void exportData_emptyRepositoriesYieldEmptySectionsAndNullSubscription() {
        var user = buildUser();
        var householdId = user.getHousehold().getId();
        var userId = user.getId();

        when(householdRepository.findById(householdId)).thenReturn(Optional.of(user.getHousehold()));
        when(userRepository.findAllByHouseholdId(householdId)).thenReturn(List.of(user));
        when(receiptRepository.findAll(ArgumentMatchers.<Specification<Receipt>>any())).thenReturn(List.of());
        when(notificationRuleRepository.findAllByUserId(userId)).thenReturn(List.of());
        when(userWatchedMarketRepository.findAllByUserId(userId)).thenReturn(List.of());
        when(subscriptionRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(householdMarketAliasRepository.findAllByHouseholdId(householdId)).thenReturn(List.of());
        when(householdCustomCategoryRepository.findByHouseholdIdOrderByName(householdId)).thenReturn(List.of());
        when(householdProductCategoryOverrideRepository.findAllByHouseholdId(householdId)).thenReturn(List.of());
        when(manualPurchaseRepository.findAllByHouseholdId(householdId)).thenReturn(List.of());
        when(shoppingListRepository.findAllByHouseholdIdOrderByCreatedAtDesc(householdId)).thenReturn(List.of());
        when(notificationRepository.findAllByUserIdOrderByCreatedAtDesc(eq(userId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        var response = userService.exportData(user);

        assertEquals(0, response.notificationRules().size());
        assertEquals(0, response.watchedMarketCnpjs().size());
        assertNull(response.subscription());
        assertEquals(0, response.marketAliases().size());
        assertEquals(0, response.customCategories().size());
        assertEquals(0, response.categoryOverrides().size());
        assertEquals(0, response.manualPurchases().size());
        assertEquals(0, response.shoppingLists().size());
        assertEquals(0, response.notifications().size());
    }
}
