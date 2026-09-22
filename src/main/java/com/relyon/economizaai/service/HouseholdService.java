package com.relyon.economizaai.service;

import com.relyon.economizaai.dto.request.JoinHouseholdRequest;
import com.relyon.economizaai.dto.response.HouseholdResponse;
import com.relyon.economizaai.exception.AlreadyInHouseholdException;
import com.relyon.economizaai.exception.InvalidInviteCodeException;
import com.relyon.economizaai.exception.NotInHouseholdException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.HouseholdRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.model.enums.MergeCategory;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Set;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class HouseholdService {

    private static final String INVITE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int INVITE_LENGTH = 6;
    private static final int MAX_INVITE_ATTEMPTS = 10;
    private static final int INVITE_TTL_HOURS = 48;
    private static final String HOUSEHOLD_MISSING_MSG = "Household missing for user ";

    private final HouseholdRepository householdRepository;
    private final UserRepository userRepository;
    private final ReceiptRepository receiptRepository;
    private final HouseholdMergeService mergeService;
    private final SecureRandom random = new SecureRandom();

    // Merge/restore is gated OFF by default — the endpoints + logic ship dark and are
    // enabled per-environment once the product flow is ready (economizaai.households.
    // merge-enabled=true). With it off, join/leave only move membership (Phase 0
    // behavior): data is never merged, but also never lost (provenance + delete guard).
    @Value("${economizaai.households.merge-enabled:false}")
    private boolean mergeEnabled;

    // Delete a now-empty household ONLY if it owns no data (as current home OR as the
    // origin of data parked elsewhere awaiting restore on split). Without this guard,
    // a 0-member household was hard-deleted, orphaning/destroying its receipts and the
    // provenance of any data that originated there. Keeps a dormant household alive as
    // a "home" until its data is restored or removed.
    private void deleteHouseholdIfEmptyAndOwnsNoData(Household household) {
        if (userRepository.countByHouseholdId(household.getId()) != 0) {
            return;
        }
        var ownsData = receiptRepository.existsByHouseholdId(household.getId())
                || receiptRepository.existsByOriginHouseholdId(household.getId());
        if (ownsData) {
            log.info("household.keep_dormant {} has no members but still owns data; not deleting", household.getId());
            return;
        }
        householdRepository.delete(household);
        log.info("Household {} deleted (no members left, no data)", household.getId());
    }

    @Transactional
    public Household createSoloHousehold() {
        var household = Household.builder()
                .inviteCode(generateUniqueInviteCode())
                .inviteCodeExpiresAt(LocalDateTime.now().plusHours(INVITE_TTL_HOURS))
                .build();
        var saved = householdRepository.save(household);
        log.info("Household {} created with invite code {} (expires {})",
                saved.getId(), saved.getInviteCode(), saved.getInviteCodeExpiresAt());
        return saved;
    }

    @Transactional
    public HouseholdResponse regenerateInviteCode(User user) {
        var household = householdRepository.findById(user.getHousehold().getId())
                .orElseThrow(() -> new IllegalStateException(HOUSEHOLD_MISSING_MSG + LogMasker.email(user.getEmail())));
        household.setInviteCode(generateUniqueInviteCode());
        household.setInviteCodeExpiresAt(LocalDateTime.now().plusHours(INVITE_TTL_HOURS));
        var saved = householdRepository.save(household);
        log.info("Household {} invite code rotated (expires {})", saved.getId(), saved.getInviteCodeExpiresAt());
        return HouseholdResponse.from(saved, userRepository.findAllByHouseholdId(saved.getId()));
    }

    @Transactional
    public HouseholdResponse removeMember(User actor, UUID memberId) {
        var household = householdRepository.findById(actor.getHousehold().getId())
                .orElseThrow(() -> new IllegalStateException(HOUSEHOLD_MISSING_MSG + LogMasker.email(actor.getEmail())));
        if (actor.getId().equals(memberId)) {
            throw new IllegalArgumentException("Use POST /households/me/leave to leave on your own — kick is for others");
        }
        var target = userRepository.findById(memberId).orElseThrow(NotInHouseholdException::new);
        if (!household.getId().equals(target.getHousehold().getId())) {
            throw new NotInHouseholdException();
        }
        var fresh = createSoloHousehold();
        target.setHousehold(fresh);
        userRepository.save(target);
        log.info("User {} kicked user {} from household {} (moved to new solo household {})",
                LogMasker.email(actor.getEmail()), LogMasker.email(target.getEmail()), household.getId(), fresh.getId());
        return HouseholdResponse.from(household, userRepository.findAllByHouseholdId(household.getId()));
    }

    @Transactional(readOnly = true)
    public HouseholdResponse getMine(User user) {
        var household = householdRepository.findById(user.getHousehold().getId())
                .orElseThrow(() -> new IllegalStateException(HOUSEHOLD_MISSING_MSG + LogMasker.email(user.getEmail())));
        var members = userRepository.findAllByHouseholdId(household.getId());
        return HouseholdResponse.from(household, members);
    }

    @Transactional
    public HouseholdResponse join(User user, JoinHouseholdRequest request) {
        var code = request.inviteCode().trim().toUpperCase();
        var target = householdRepository.findByInviteCode(code)
                .orElseThrow(() -> new InvalidInviteCodeException(code));

        if (target.getInviteCodeExpiresAt() != null
                && target.getInviteCodeExpiresAt().isBefore(LocalDateTime.now())) {
            throw new InvalidInviteCodeException(code);
        }

        if (target.getId().equals(user.getHousehold().getId())) {
            throw new AlreadyInHouseholdException();
        }

        var previous = user.getHousehold();
        user.setHousehold(target);
        userRepository.save(user);
        log.info("User {} joined household {} (left {})", LogMasker.email(user.getEmail()), target.getId(), previous.getId());

        // Optional data merge (Phase 1, feature-flagged). Runs BEFORE the delete guard
        // so moved data lands in the target; anything shadowed (collision) stays parked
        // on `previous`, which then keeps it alive as a dormant home for later restore.
        if (mergeEnabled && Boolean.TRUE.equals(request.bringData())) {
            var categories = resolveCategories(request.mergeCategories());
            var result = mergeService.merge(previous, target, categories);
            log.info("join.merge user={} categories={} moved={} shadowed={}",
                    LogMasker.email(user.getEmail()), categories, result.moved(), result.shadowed());
        }

        deleteHouseholdIfEmptyAndOwnsNoData(previous);

        var members = userRepository.findAllByHouseholdId(target.getId());
        return HouseholdResponse.from(target, members);
    }

    // Empty/null selection = merge ALL categories; otherwise just the chosen ones.
    private Set<MergeCategory> resolveCategories(Set<MergeCategory> requested) {
        return (requested == null || requested.isEmpty())
                ? EnumSet.allOf(MergeCategory.class)
                : EnumSet.copyOf(requested);
    }

    @Transactional
    public HouseholdResponse leave(User user) {
        var previous = user.getHousehold();

        // Where does the leaver land? If merge is enabled and the leaver has a dormant
        // ORIGIN household still alive (data they brought in, kept as their "home"),
        // reclaim it and restore everything that originated there — including rows that
        // were shadowed (parked) during the merge. Otherwise, a fresh solo household.
        Household home;
        if (mergeEnabled) {
            home = reclaimOriginHomeOrFresh(user);
            user.setHousehold(home);
            userRepository.save(user);
            var restored = mergeService.restoreOriginals(home);
            log.info("leave.restore user={} home={} restored={}",
                    LogMasker.email(user.getEmail()), home.getId(), restored.moved());
        } else {
            home = createSoloHousehold();
            user.setHousehold(home);
            userRepository.save(user);
        }
        log.info("User {} left household {} for household {}", LogMasker.email(user.getEmail()), previous.getId(), home.getId());

        deleteHouseholdIfEmptyAndOwnsNoData(previous);

        return HouseholdResponse.from(home, userRepository.findAllByHouseholdId(home.getId()));
    }

    // The leaver's "home" to return to: their data's origin household if one still
    // exists and differs from the one they're leaving; else a fresh solo household.
    // We read origin from the leaver's OWN receipts (user_id is the stable owner), so
    // a user always lands back where their data came from.
    private Household reclaimOriginHomeOrFresh(User user) {
        var currentId = user.getHousehold().getId();
        var originId = receiptRepository.findFirstByUserIdAndOriginHouseholdIdNotOrderByCreatedAtAsc(
                        user.getId(), currentId)
                .map(r -> r.getOriginHousehold().getId())
                .orElse(null);
        if (originId != null) {
            var origin = householdRepository.findById(originId).orElse(null);
            if (origin != null) {
                return origin;
            }
        }
        return createSoloHousehold();
    }

    private String generateUniqueInviteCode() {
        for (var attempt = 0; attempt < MAX_INVITE_ATTEMPTS; attempt++) {
            var code = randomCode();
            if (!householdRepository.existsByInviteCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("Could not generate a unique invite code after " + MAX_INVITE_ATTEMPTS + " attempts");
    }

    private String randomCode() {
        var sb = new StringBuilder(INVITE_LENGTH);
        for (var i = 0; i < INVITE_LENGTH; i++) {
            sb.append(INVITE_ALPHABET.charAt(random.nextInt(INVITE_ALPHABET.length())));
        }
        return sb.toString();
    }
}
