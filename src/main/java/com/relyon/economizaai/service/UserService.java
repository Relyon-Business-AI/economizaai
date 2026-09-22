package com.relyon.economizaai.service;

import com.relyon.economizaai.dto.request.ChangePasswordRequest;
import com.relyon.economizaai.dto.request.LoginRequest;
import com.relyon.economizaai.dto.request.RegisterRequest;
import com.relyon.economizaai.dto.request.UpdateContributionRequest;
import com.relyon.economizaai.dto.request.UpdateHomeLocationRequest;
import com.relyon.economizaai.dto.request.UpdateUserRequest;
import com.relyon.economizaai.dto.response.AuthResponse;
import com.relyon.economizaai.dto.response.HouseholdResponse;
import com.relyon.economizaai.dto.response.NotificationResponse;
import com.relyon.economizaai.dto.response.NotificationRuleResponse;
import com.relyon.economizaai.dto.response.ReceiptResponse;
import com.relyon.economizaai.dto.response.UserDataExportResponse;
import com.relyon.economizaai.dto.response.UserDataExportResponse.AccountExtras;
import com.relyon.economizaai.dto.response.UserDataExportResponse.CategoryOverride;
import com.relyon.economizaai.dto.response.UserDataExportResponse.CustomCategory;
import com.relyon.economizaai.dto.response.UserDataExportResponse.ManualPurchaseSummary;
import com.relyon.economizaai.dto.response.UserDataExportResponse.MarketAlias;
import com.relyon.economizaai.dto.response.UserDataExportResponse.ShoppingListSummary;
import com.relyon.economizaai.dto.response.UserDataExportResponse.ShoppingListItemSummary;
import com.relyon.economizaai.dto.response.UserDataExportResponse.SubscriptionSummary;
import com.relyon.economizaai.dto.response.UserDataExportResponse.NotificationPreferenceSummary;
import com.relyon.economizaai.dto.response.UserDataExportResponse.ProductAliasSummary;
import com.relyon.economizaai.dto.response.UserDataExportResponse.BrandPreferenceSummary;
import com.relyon.economizaai.dto.response.UserDataExportResponse.ConsumptionSnoozeSummary;
import com.relyon.economizaai.dto.response.UserDataExportResponse.RecentViewSummary;
import com.relyon.economizaai.dto.response.UserDataExportResponse.NotificationEventSummary;
import com.relyon.economizaai.dto.response.UserDataExportResponse.DealSurfaceStateSummary;
import com.relyon.economizaai.dto.response.UserDataExportResponse.DataShareConsentSummary;
import com.relyon.economizaai.dto.response.UserResponse;
import com.relyon.economizaai.exception.EmailAlreadyExistsException;
import com.relyon.economizaai.exception.InvalidCredentialsException;
import com.relyon.economizaai.exception.SocialAccountLoginException;
import com.relyon.economizaai.model.enums.AuthProvider;
import com.relyon.economizaai.exception.InvalidCurrentPasswordException;
import com.relyon.economizaai.exception.InvalidLegalVersionException;
import com.relyon.economizaai.legal.LegalDocuments;
import com.relyon.economizaai.model.HouseholdProductAlias;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.service.analytics.meta.MetaConversionsService;
import com.relyon.economizaai.service.attribution.AttributionResolver;
import com.relyon.economizaai.repository.HouseholdCustomCategoryRepository;
import com.relyon.economizaai.repository.HouseholdMarketAliasRepository;
import com.relyon.economizaai.repository.HouseholdProductCategoryOverrideRepository;
import com.relyon.economizaai.repository.HouseholdRepository;
import com.relyon.economizaai.repository.ManualPurchaseRepository;
import com.relyon.economizaai.repository.NotificationRepository;
import com.relyon.economizaai.repository.NotificationRuleRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.repository.ShoppingListRepository;
import com.relyon.economizaai.repository.SubscriptionRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.repository.UserWatchedMarketRepository;
import com.relyon.economizaai.service.subscription.SubscriptionService;
import com.relyon.economizaai.repository.NotificationPreferenceRepository;
import com.relyon.economizaai.repository.NotificationEventRepository;
import com.relyon.economizaai.repository.ConsumptionSnoozeRepository;
import com.relyon.economizaai.repository.ManualBrandPreferenceRepository;
import com.relyon.economizaai.repository.ProductRecentViewRepository;
import com.relyon.economizaai.repository.DealSurfaceStateRepository;
import com.relyon.economizaai.repository.HouseholdProductAliasRepository;
import com.relyon.economizaai.repository.DataShareConsentRepository;
import com.relyon.economizaai.model.UserWatchedMarket;
import com.relyon.economizaai.security.JwtService;
import com.relyon.economizaai.service.auth.EmailVerificationService;
import com.relyon.economizaai.service.auth.LoginActivityRecorder;
import com.relyon.economizaai.service.auth.SignupAlertService;
import com.relyon.economizaai.service.auth.RefreshTokenService;
import com.relyon.economizaai.service.notifications.NotificationRuleService;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final HouseholdRepository householdRepository;
    private final ReceiptRepository receiptRepository;
    private final NotificationRuleRepository notificationRuleRepository;
    private final UserWatchedMarketRepository userWatchedMarketRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final HouseholdMarketAliasRepository householdMarketAliasRepository;
    private final HouseholdCustomCategoryRepository householdCustomCategoryRepository;
    private final HouseholdProductCategoryOverrideRepository householdProductCategoryOverrideRepository;
    private final ManualPurchaseRepository manualPurchaseRepository;
    private final ShoppingListRepository shoppingListRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationPreferenceRepository notificationPreferenceRepository;
    private final NotificationEventRepository notificationEventRepository;
    private final ConsumptionSnoozeRepository consumptionSnoozeRepository;
    private final ManualBrandPreferenceRepository manualBrandPreferenceRepository;
    private final ProductRecentViewRepository productRecentViewRepository;
    private final DealSurfaceStateRepository dealSurfaceStateRepository;
    private final HouseholdProductAliasRepository householdProductAliasRepository;
    private final DataShareConsentRepository dataShareConsentRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final HouseholdService householdService;
    private final EmailVerificationService emailVerificationService;
    private final RefreshTokenService refreshTokenService;
    private final NotificationRuleService notificationRuleService;
    private final LoginActivityRecorder loginActivityRecorder;
    private final SubscriptionService subscriptionService;
    private final SignupAlertService signupAlertService;
    private final MetaConversionsService metaConversionsService;
    private final AttributionResolver attributionResolver;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyExistsException(request.email());
        }
        if (!LegalDocuments.CURRENT_TERMS_VERSION.equals(request.acceptedTermsVersion())) {
            throw new InvalidLegalVersionException("terms",
                    request.acceptedTermsVersion(), LegalDocuments.CURRENT_TERMS_VERSION);
        }
        if (!LegalDocuments.CURRENT_PRIVACY_VERSION.equals(request.acceptedPrivacyVersion())) {
            throw new InvalidLegalVersionException("privacy-policy",
                    request.acceptedPrivacyVersion(), LegalDocuments.CURRENT_PRIVACY_VERSION);
        }

        var household = householdService.createSoloHousehold();
        var user = User.builder()
                .name(request.name())
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .locale(LocalizedMessageService.requestLocaleTag())
                .household(household)
                .acceptedTermsVersion(request.acceptedTermsVersion())
                .acceptedPrivacyVersion(request.acceptedPrivacyVersion())
                .acceptedLegalAt(LocalDateTime.now())
                .build();
        attributionResolver.applyTo(user, request.attribution());

        var savedUser = userRepository.save(user);
        notificationRuleService.ensureDefaults(savedUser);
        var signupPromoValidUntil = subscriptionService.grantSignupPromoIfEnabled(savedUser);
        loginActivityRecorder.recordRegistration(savedUser, request.platform());
        signupAlertService.notifyNewAccount(savedUser, "e-mail/senha");
        metaConversionsService.reportCompleteRegistration(savedUser, "email");
        emailVerificationService.sendVerificationFor(savedUser);
        var token = jwtService.generateToken(savedUser);
        var refreshToken = refreshTokenService.issue(savedUser);
        log.info("New user registered: {} (household {}, terms v{}, privacy v{})",
                LogMasker.email(savedUser.getEmail()), household.getId(),
                savedUser.getAcceptedTermsVersion(), savedUser.getAcceptedPrivacyVersion());
        return new AuthResponse(token, refreshToken, UserResponse.from(savedUser),
                signupPromoValidUntil != null, signupPromoValidUntil);
    }

    public AuthResponse login(LoginRequest request) {
        var found = userRepository.findByEmail(request.email());
        // A social-login account (Google/Apple) has no local password, so a password
        // attempt would only ever yield a generic "invalid credentials". Detect it and
        // point the user at the right provider button instead.
        found.filter(candidate -> candidate.getPassword() == null
                        && candidate.getAuthProvider() != AuthProvider.LOCAL)
                .ifPresent(socialUser -> {
                    throw new SocialAccountLoginException(socialUser.getAuthProvider());
                });

        var user = found
                .filter(foundUser -> passwordEncoder.matches(request.password(), foundUser.getPassword()))
                .orElseThrow(InvalidCredentialsException::new);

        loginActivityRecorder.recordLogin(user, request.platform());
        var token = jwtService.generateToken(user);
        var refreshToken = refreshTokenService.issue(user);
        log.info("User logged in: {}", LogMasker.email(user.getEmail()));
        return new AuthResponse(token, refreshToken, UserResponse.from(user), false, null);
    }

    public UserResponse getProfile(User user) {
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse updateProfile(User user, UpdateUserRequest request) {
        user.setName(request.name());
        var updatedUser = userRepository.save(user);
        log.info("User profile updated: {}", LogMasker.email(updatedUser.getEmail()));
        return UserResponse.from(updatedUser);
    }

    @Transactional
    public void changePassword(User user, ChangePasswordRequest request) {
        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            throw new InvalidCurrentPasswordException();
        }

        user.setPassword(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
        log.info("User {} changed password", LogMasker.email(user.getEmail()));
    }

    @Transactional
    public UserResponse updateContribution(User user, UpdateContributionRequest request) {
        user.setContributionOptIn(request.contributionOptIn());
        var saved = userRepository.save(user);
        log.info("User {} contributionOptIn={}", LogMasker.email(saved.getEmail()), saved.isContributionOptIn());
        return UserResponse.from(saved);
    }

    @Transactional
    public UserResponse updateHomeLocation(User user, UpdateHomeLocationRequest request) {
        user.setHomeLatitude(request.latitude());
        user.setHomeLongitude(request.longitude());
        user.setHomeSetAt(LocalDateTime.now());
        var saved = userRepository.save(user);
        log.info("User {} home location set ({}, {})", LogMasker.email(saved.getEmail()), saved.getHomeLatitude(), saved.getHomeLongitude());
        return UserResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public UserDataExportResponse exportData(User user) {
        var userId = user.getId();
        var householdId = user.getHousehold().getId();
        var household = householdRepository.findById(householdId)
                .orElseThrow(() -> new IllegalStateException("Household missing for user " + LogMasker.email(user.getEmail())));
        var members = userRepository.findAllByHouseholdId(householdId);

        var accountData = collectAccountData(user, userId);
        var customizations = collectHouseholdCustomizations(householdId);
        var purchaseData = collectPurchaseData(userId, householdId);

        log.info("Data export for user {}: {} receipts, {} rules, {} watched, {} aliases, {} overrides, {} manual, {} lists, {} notifications",
                LogMasker.email(user.getEmail()), purchaseData.receipts().size(), accountData.notificationRules().size(),
                accountData.watchedMarketCnpjs().size(), customizations.marketAliases().size(),
                customizations.categoryOverrides().size(), purchaseData.manualPurchases().size(),
                purchaseData.shoppingLists().size(), accountData.notifications().size());

        return new UserDataExportResponse(
                UserResponse.from(user),
                accountData.extras(),
                HouseholdResponse.from(household, members),
                purchaseData.receipts(),
                accountData.notificationRules(),
                accountData.notificationPreferences(),
                accountData.watchedMarketCnpjs(),
                accountData.subscription(),
                customizations.marketAliases(),
                customizations.customCategories(),
                customizations.categoryOverrides(),
                customizations.productAliases(),
                customizations.brandPreferences(),
                purchaseData.manualPurchases(),
                customizations.consumptionSnoozes(),
                purchaseData.shoppingLists(),
                accountData.recentlyViewedProducts(),
                accountData.notifications(),
                accountData.notificationEvents(),
                accountData.dealSurfaceStates(),
                accountData.dataShareConsents(),
                LocalDateTime.now()
        );
    }

    private AccountData collectAccountData(User user, UUID userId) {
        var extras = new AccountExtras(
                user.getPushDeviceToken(),
                user.getPushTokenUpdatedAt(),
                user.isEmailVerified(),
                user.getEmailVerifiedAt(),
                user.isContributionOptIn(),
                user.getPhoneNumber(),
                user.isPhoneVerified(),
                user.getProfilePictureKey(),
                user.getProfilePictureContentType(),
                user.getProfilePictureUploadedAt(),
                user.getDigestFrequency() == null ? null : user.getDigestFrequency().name(),
                user.getDigestSendHour(),
                user.getHomeSetAt(),
                user.getAuthProvider() == null ? null : user.getAuthProvider().name(),
                user.getProviderSubject(),
                user.getAcceptedTermsVersion(),
                user.getAcceptedPrivacyVersion(),
                user.getAcceptedLegalAt());

        var rules = notificationRuleRepository.findAllByUserId(userId);
        var ruleProductIds = rules.stream()
                .filter(rule -> rule.getProduct() != null)
                .map(rule -> rule.getProduct().getId())
                .distinct()
                .toList();
        var ruleFriendlyNames = ruleProductIds.isEmpty() ? Map.<UUID, String>of()
                : householdProductAliasRepository
                        .findAllByHouseholdIdAndProductIdIn(user.getHousehold().getId(), ruleProductIds).stream()
                        .collect(Collectors.toMap(alias -> alias.getProduct().getId(),
                                HouseholdProductAlias::getFriendlyName));
        var notificationRules = rules.stream()
                .map(rule -> NotificationRuleResponse.from(rule,
                        rule.getProduct() != null ? ruleFriendlyNames.get(rule.getProduct().getId()) : null))
                .toList();

        var notificationPreferences = notificationPreferenceRepository.findAllByUserId(userId).stream()
                .map(preference -> new NotificationPreferenceSummary(
                        preference.getType().name(), preference.getChannel().name()))
                .toList();

        var watchedMarketCnpjs = userWatchedMarketRepository.findAllByUserId(userId).stream()
                .map(UserWatchedMarket::getMarketCnpj)
                .toList();

        var subscription = subscriptionRepository.findByUserId(userId)
                .map(record -> new SubscriptionSummary(
                        record.getProvider(), record.getStatus(), record.getCurrentPeriodEnd()))
                .orElse(null);

        var notifications = notificationRepository
                .findAllByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 500))
                .getContent().stream()
                .map(NotificationResponse::from)
                .toList();

        var notificationEvents = notificationEventRepository.findAllByUserId(userId).stream()
                .map(event -> new NotificationEventSummary(
                        event.getEventType().name(), event.getProductId(), event.getMarketCnpj(),
                        event.getChannel(), event.getSavingsAmount(), event.getOccurredAt()))
                .toList();

        var recentlyViewed = productRecentViewRepository.findRecentByUserId(userId, PageRequest.of(0, 500)).stream()
                .map(view -> new RecentViewSummary(view.getProduct().getId(), view.getViewedAt()))
                .toList();

        var dealSurfaceStates = dealSurfaceStateRepository.findAllByUserId(userId).stream()
                .map(state -> new DealSurfaceStateSummary(
                        state.getProductId(), state.getMarketCnpj(), state.getLastSurfacedAt()))
                .toList();

        // Consents where the user is grantor OR requester (deduped by id).
        var consentsById = new LinkedHashMap<UUID, DataShareConsentSummary>();
        Stream.concat(dataShareConsentRepository.findByGrantorId(userId).stream(),
                        dataShareConsentRepository.findByRequesterId(userId).stream())
                .forEach(consent -> consentsById.putIfAbsent(consent.getId(), new DataShareConsentSummary(
                        consent.getStatus().name(), consent.getScope().name(), consent.getResolvedAt())));
        var dataShareConsents = List.copyOf(consentsById.values());

        return new AccountData(extras, notificationRules, notificationPreferences, watchedMarketCnpjs,
                subscription, notifications, notificationEvents, recentlyViewed, dealSurfaceStates, dataShareConsents);
    }

    private HouseholdCustomizations collectHouseholdCustomizations(UUID householdId) {
        var marketAliases = householdMarketAliasRepository.findAllByHouseholdId(householdId).stream()
                .map(alias -> new MarketAlias(alias.getMarketCnpj(), alias.getCustomName()))
                .toList();

        var customCategories = householdCustomCategoryRepository.findByHouseholdIdOrderByName(householdId).stream()
                .map(category -> new CustomCategory(category.getId(), category.getName()))
                .toList();

        var categoryOverrides = householdProductCategoryOverrideRepository.findAllByHouseholdId(householdId).stream()
                .map(override -> new CategoryOverride(override.getProduct().getId(), override.effectiveLabel()))
                .toList();

        var productAliases = householdProductAliasRepository.findAllByHouseholdId(householdId).stream()
                .map(alias -> new ProductAliasSummary(alias.getProduct().getId(), alias.getFriendlyName()))
                .toList();

        var brandPreferences = manualBrandPreferenceRepository.findAllByHouseholdId(householdId).stream()
                .map(preference -> new BrandPreferenceSummary(
                        preference.getGenericName(), preference.getBrand(), preference.getStrength().name()))
                .toList();

        var consumptionSnoozes = consumptionSnoozeRepository.findAllByHouseholdId(householdId).stream()
                .map(snooze -> new ConsumptionSnoozeSummary(snooze.getProduct().getId(), snooze.getSnoozedUntil()))
                .toList();

        return new HouseholdCustomizations(marketAliases, customCategories, categoryOverrides,
                productAliases, brandPreferences, consumptionSnoozes);
    }

    private PurchaseData collectPurchaseData(UUID userId, UUID householdId) {
        var receipts = receiptRepository
                .findAll((root, query, builder) -> builder.equal(root.get("user").get("id"), userId)).stream()
                .map(ReceiptResponse::from)
                .toList();

        var manualPurchases = manualPurchaseRepository.findAllByHouseholdId(householdId).stream()
                .map(purchase -> new ManualPurchaseSummary(
                        purchase.getProduct().getId(), purchase.getQuantity(), purchase.getPurchasedAt()))
                .toList();

        var shoppingLists = shoppingListRepository.findAllByHouseholdIdOrderByCreatedAtDesc(householdId).stream()
                .map(list -> new ShoppingListSummary(list.getId(), list.getName(),
                        list.getItems().stream()
                                .map(item -> new ShoppingListItemSummary(
                                        item.getProduct() == null ? null : item.getProduct().getId(),
                                        item.getFreeText(), item.getQuantity(), item.isChecked()))
                                .toList()))
                .toList();

        return new PurchaseData(receipts, manualPurchases, shoppingLists);
    }

    private record AccountData(AccountExtras extras,
                               List<NotificationRuleResponse> notificationRules,
                               List<NotificationPreferenceSummary> notificationPreferences,
                               List<String> watchedMarketCnpjs,
                               SubscriptionSummary subscription,
                               List<NotificationResponse> notifications,
                               List<NotificationEventSummary> notificationEvents,
                               List<RecentViewSummary> recentlyViewedProducts,
                               List<DealSurfaceStateSummary> dealSurfaceStates,
                               List<DataShareConsentSummary> dataShareConsents) {}

    private record HouseholdCustomizations(List<MarketAlias> marketAliases,
                                           List<CustomCategory> customCategories,
                                           List<CategoryOverride> categoryOverrides,
                                           List<ProductAliasSummary> productAliases,
                                           List<BrandPreferenceSummary> brandPreferences,
                                           List<ConsumptionSnoozeSummary> consumptionSnoozes) {}

    private record PurchaseData(List<ReceiptResponse> receipts,
                                List<ManualPurchaseSummary> manualPurchases,
                                List<ShoppingListSummary> shoppingLists) {}

    @Transactional
    public void deleteAccount(User user) {
        var householdId = user.getHousehold().getId();
        userRepository.delete(user);
        log.info("User account deleted: {}", LogMasker.email(user.getEmail()));
        if (userRepository.countByHouseholdId(householdId) == 0) {
            householdRepository.deleteById(householdId);
            log.info("Household {} deleted (no members left after user deletion)", householdId);
        }
    }
}
