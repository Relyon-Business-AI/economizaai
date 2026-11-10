package com.relyon.economizaai.service;

import com.relyon.economizaai.dto.request.JoinHouseholdRequest;
import com.relyon.economizaai.dto.response.HouseholdResponse;
import com.relyon.economizaai.dto.response.InvitePreviewResponse;
import com.relyon.economizaai.exception.AlreadyInHouseholdException;
import com.relyon.economizaai.exception.HouseholdFullException;
import com.relyon.economizaai.exception.InvalidInviteCodeException;
import com.relyon.economizaai.exception.NotHouseholdHostException;
import com.relyon.economizaai.exception.NotInHouseholdException;
import com.relyon.economizaai.exception.SoloHouseholdLeaveException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.MergeCategory;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.HouseholdRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.cache.HouseholdCacheGen;
import com.relyon.economizaai.service.notifications.NotificationPayload;
import com.relyon.economizaai.service.notifications.NotificationService;
import com.relyon.economizaai.service.privacy.LogMasker;
import com.relyon.economizaai.service.subscription.SubscriptionGateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
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

    // Oldest account first — the deterministic "who inherits the host role" order.
    private static final Comparator<User> BY_SENIORITY =
            Comparator.comparing(User::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(User::getId);

    private final HouseholdRepository householdRepository;
    private final UserRepository userRepository;
    private final ReceiptRepository receiptRepository;
    private final HouseholdMergeService mergeService;
    private final HouseholdCacheGen householdCacheGen;
    private final SubscriptionGateService subscriptionGate;
    private final NotificationService notificationService;
    private final LocalizedMessageService messageService;
    private final SecureRandom random = new SecureRandom();

    // Merge/restore: ON since household sharing launched (2026-11). Joining MOVES the
    // joiner's data into the shared household; leaving/kick restores what they brought.
    // Off = Phase 0 (membership-only): data is never merged, but also never lost
    // (provenance + delete guard keep it parked in a dormant household).
    @Value("${economizaai.households.merge-enabled:true}")
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
        // Invite code is a bearer credential (grants household membership) — masked.
        log.info("Household {} created with invite code {} (expires {})",
                saved.getId(), LogMasker.token(saved.getInviteCode()), saved.getInviteCodeExpiresAt());
        return saved;
    }

    /** Make {@code user} the host of a household created for them (registration, split). */
    @Transactional
    public void assignHost(Household household, User user) {
        household.setOwner(user);
        householdRepository.save(household);
    }

    @Transactional
    public HouseholdResponse regenerateInviteCode(User user) {
        var household = loadHouseholdOf(user);
        household.setInviteCode(generateUniqueInviteCode());
        household.setInviteCodeExpiresAt(LocalDateTime.now().plusHours(INVITE_TTL_HOURS));
        var saved = householdRepository.save(household);
        log.info("Household {} invite code rotated (expires {})", saved.getId(), saved.getInviteCodeExpiresAt());
        return respond(saved);
    }

    /** Any member may rename; blank clears the name (the app falls back to a default). */
    @Transactional
    public HouseholdResponse rename(User user, String name) {
        var household = loadHouseholdOf(user);
        var trimmed = name == null ? null : name.trim();
        household.setName(trimmed == null || trimmed.isEmpty() ? null : trimmed);
        var saved = householdRepository.save(household);
        log.info("household.renamed {} by {}", saved.getId(), LogMasker.email(user.getEmail()));
        return respond(saved);
    }

    /**
     * Only the host may remove someone else. The removed member lands back in their
     * origin household with everything they brought (same restore as leaving).
     */
    @Transactional
    public HouseholdResponse removeMember(User actor, UUID memberId) {
        var household = loadHouseholdOf(actor);
        var members = membersOf(household);
        requireHost(actor, household, members);
        if (actor.getId().equals(memberId)) {
            throw new IllegalArgumentException("Use POST /households/leave to leave on your own — kick is for others");
        }
        var target = userRepository.findById(memberId).orElseThrow(NotInHouseholdException::new);
        if (!household.getId().equals(target.getHousehold().getId())) {
            throw new NotInHouseholdException();
        }
        var home = relocateToHome(target);
        householdCacheGen.bump(household.getId());
        log.info("User {} kicked user {} from household {} (relocated to household {})",
                LogMasker.email(actor.getEmail()), LogMasker.email(target.getEmail()), household.getId(), home.getId());

        var remaining = members.stream().filter(member -> !member.getId().equals(target.getId())).toList();
        notifyMembers(remaining, actor, NotificationType.HOUSEHOLD_MEMBER_LEFT, target.getName());
        return HouseholdResponse.from(household, remaining);
    }

    @Transactional
    public HouseholdResponse getMine(User user) {
        var household = loadHouseholdOf(user);
        return respond(household);
    }

    /** What the code unlocks, shown before the user commits to joining. */
    @Transactional(readOnly = true)
    public InvitePreviewResponse previewInvite(String rawCode) {
        var target = findValidByInviteCode(rawCode);
        var members = membersOf(target);
        var host = resolveHost(target, members);
        return new InvitePreviewResponse(
                target.getName(),
                host == null ? null : firstName(host.getName()),
                members.size(),
                members.stream().map(member -> firstName(member.getName())).toList());
    }

    @Transactional
    public HouseholdResponse join(User user, JoinHouseholdRequest request) {
        var target = findValidByInviteCode(request.inviteCode());
        if (target.getId().equals(user.getHousehold().getId())) {
            throw new AlreadyInHouseholdException();
        }
        var currentMembers = membersOf(target);
        requireRoom(target, currentMembers, user);

        var previous = user.getHousehold();
        user.setHousehold(target);
        userRepository.save(user);
        log.info("User {} joined household {} (left {})", LogMasker.email(user.getEmail()), target.getId(), previous.getId());

        // Data merge runs BEFORE the delete guard so moved data lands in the target;
        // anything shadowed (collision) stays parked on `previous`, which then keeps it
        // alive as a dormant home for later restore. Default is to bring everything.
        if (mergeEnabled && !Boolean.FALSE.equals(request.bringData())) {
            var categories = resolveCategories(request.mergeCategories());
            var result = mergeService.merge(previous, target, categories);
            log.info("join.merge user={} categories={} moved={} shadowed={}",
                    LogMasker.email(user.getEmail()), categories, result.moved(), result.shadowed());
        }
        householdCacheGen.bump(previous.getId());
        householdCacheGen.bump(target.getId());

        deleteHouseholdIfEmptyAndOwnsNoData(previous);

        notifyMembers(currentMembers, user, NotificationType.HOUSEHOLD_MEMBER_JOINED, user.getName());
        return respond(target);
    }

    @Transactional
    public HouseholdResponse leave(User user) {
        var previous = user.getHousehold();
        var members = membersOf(previous);
        if (members.size() <= 1) {
            throw new SoloHouseholdLeaveException();
        }

        var home = relocateToHome(user);
        householdCacheGen.bump(previous.getId());
        log.info("User {} left household {} for household {}", LogMasker.email(user.getEmail()), previous.getId(), home.getId());

        var remaining = members.stream().filter(member -> !member.getId().equals(user.getId())).toList();
        transferHostIfLeaving(previous, user, remaining);
        deleteHouseholdIfEmptyAndOwnsNoData(previous);

        notifyMembers(remaining, user, NotificationType.HOUSEHOLD_MEMBER_LEFT, user.getName());
        return HouseholdResponse.from(home, List.of(user));
    }

    // ---------- membership moves ----------

    /**
     * Where a leaver (or kicked member) lands. With merge on: their dormant ORIGIN
     * household if one still exists (data they brought in, kept as their "home"), with
     * everything that originated there restored — including rows shadowed (parked)
     * during the merge. Otherwise a fresh solo household. They host wherever they land.
     */
    private Household relocateToHome(User user) {
        Household home;
        if (mergeEnabled) {
            home = reclaimOriginHomeOrFresh(user);
            user.setHousehold(home);
            userRepository.save(user);
            var restored = mergeService.restoreOriginals(home);
            log.info("relocate.restore user={} home={} restored={}",
                    LogMasker.email(user.getEmail()), home.getId(), restored.moved());
        } else {
            home = createSoloHousehold();
            user.setHousehold(home);
            userRepository.save(user);
        }
        if (home.getOwner() == null) {
            home.setOwner(user);
            home = householdRepository.save(home);
        }
        householdCacheGen.bump(home.getId());
        return home;
    }

    // The leaver's "home" to return to: their data's origin household if one still
    // exists and differs from the one they're leaving; else a fresh solo household.
    // We read origin from the leaver's OWN receipts (user_id is the stable owner), so
    // a user always lands back where their data came from.
    private Household reclaimOriginHomeOrFresh(User user) {
        var currentId = user.getHousehold().getId();
        var originId = receiptRepository.findFirstByUserIdAndOriginHouseholdIdNotOrderByCreatedAtAsc(
                        user.getId(), currentId)
                .map(receipt -> receipt.getOriginHousehold().getId())
                .orElse(null);
        if (originId != null) {
            var origin = householdRepository.findById(originId).orElse(null);
            if (origin != null) {
                return origin;
            }
        }
        return createSoloHousehold();
    }

    // ---------- host (anfitrião) ----------

    private void requireHost(User actor, Household household, List<User> members) {
        var host = resolveHost(household, members);
        if (host == null || !host.getId().equals(actor.getId())) {
            throw new NotHouseholdHostException();
        }
    }

    /**
     * The host, self-healing a missing or stale one (account deleted, legacy row) to the
     * most senior current member. Null only for an empty household.
     */
    private User resolveHost(Household household, List<User> members) {
        var owner = household.getOwner();
        if (owner != null) {
            var ownerId = owner.getId();
            var stillMember = members.stream().filter(member -> member.getId().equals(ownerId)).findFirst();
            if (stillMember.isPresent()) {
                return stillMember.get();
            }
        }
        var heir = members.stream().min(BY_SENIORITY).orElse(null);
        if (heir != null) {
            household.setOwner(heir);
            householdRepository.save(household);
            log.info("household.host_assigned household={} host={}", household.getId(), LogMasker.email(heir.getEmail()));
        }
        return heir;
    }

    private void transferHostIfLeaving(Household household, User leaver, List<User> remaining) {
        var owner = household.getOwner();
        if (owner != null && !owner.getId().equals(leaver.getId())) {
            return;
        }
        resolveHost(household, remaining);
    }

    // ---------- capacity ----------

    private void requireRoom(Household target, List<User> currentMembers, User joiner) {
        var everyone = new ArrayList<>(currentMembers);
        everyone.add(joiner);
        var cap = subscriptionGate.householdMemberCap(everyone);
        if (currentMembers.size() >= cap.limit()) {
            log.info("household.full household={} members={} limit={} upgradeAvailable={}",
                    target.getId(), currentMembers.size(), cap.limit(), cap.upgradeAvailable());
            throw new HouseholdFullException(cap.limit(), cap.upgradeAvailable());
        }
    }

    // ---------- notifications ----------

    private void notifyMembers(List<User> recipients, User actor, NotificationType type, String subjectName) {
        var keyPrefix = type == NotificationType.HOUSEHOLD_MEMBER_JOINED
                ? "household.member.joined" : "household.member.left";
        recipients.stream()
                .filter(recipient -> !recipient.getId().equals(actor.getId()))
                .forEach(recipient -> {
                    var locale = LocalizedMessageService.toLocale(recipient.getLocale());
                    notificationService.notifyAfterCommit(new NotificationPayload(recipient, type,
                            messageService.translate(keyPrefix + ".title", locale, subjectName),
                            messageService.translate(keyPrefix + ".body", locale, subjectName), null));
                });
    }

    // ---------- helpers ----------

    private Household loadHouseholdOf(User user) {
        return householdRepository.findById(user.getHousehold().getId())
                .orElseThrow(() -> new IllegalStateException(HOUSEHOLD_MISSING_MSG + LogMasker.email(user.getEmail())));
    }

    private List<User> membersOf(Household household) {
        return userRepository.findAllByHouseholdId(household.getId()).stream().sorted(BY_SENIORITY).toList();
    }

    private HouseholdResponse respond(Household household) {
        var members = membersOf(household);
        resolveHost(household, members);
        return HouseholdResponse.from(household, members);
    }

    private Household findValidByInviteCode(String rawCode) {
        var code = rawCode.trim().toUpperCase();
        var target = householdRepository.findByInviteCode(code)
                .orElseThrow(() -> new InvalidInviteCodeException(code));
        if (target.getInviteCodeExpiresAt() != null
                && target.getInviteCodeExpiresAt().isBefore(LocalDateTime.now())) {
            throw new InvalidInviteCodeException(code);
        }
        return target;
    }

    // Empty/null selection = merge ALL categories; otherwise just the chosen ones.
    private Set<MergeCategory> resolveCategories(Set<MergeCategory> requested) {
        return (requested == null || requested.isEmpty())
                ? EnumSet.allOf(MergeCategory.class)
                : EnumSet.copyOf(requested);
    }

    private static String firstName(String fullName) {
        if (fullName == null || fullName.isBlank()) return null;
        return fullName.trim().split("\\s+")[0];
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
