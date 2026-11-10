package com.relyon.economizaai.service;

import com.relyon.economizaai.dto.request.JoinHouseholdRequest;
import com.relyon.economizaai.dto.response.HouseholdResponse;
import com.relyon.economizaai.dto.response.InvitePreviewResponse;
import com.relyon.economizaai.exception.AlreadyInHouseholdException;
import com.relyon.economizaai.exception.HouseholdFullException;
import com.relyon.economizaai.exception.HouseholdLeaveFirstException;
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

    // Who has been in the household longest: the original host (never joined — null
    // joined-at) first, then guests by join time. Decides who inherits the host role.
    private static final Comparator<User> BY_SENIORITY =
            Comparator.comparing(User::getHouseholdJoinedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(User::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(User::getId);

    private final HouseholdRepository householdRepository;
    private final UserRepository userRepository;
    private final ReceiptRepository receiptRepository;
    private final HouseholdMergeService mergeService;
    private final HouseholdSplitService splitService;
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
        if (userRepository.existsByHomeHouseholdId(household.getId())) {
            log.info("household.keep_dormant {} is the home of a member away as a guest; not deleting", household.getId());
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
        requireHost(user, household, membersOf(household));
        household.setInviteCode(generateUniqueInviteCode());
        household.setInviteCodeExpiresAt(LocalDateTime.now().plusHours(INVITE_TTL_HOURS));
        var saved = householdRepository.save(household);
        log.info("Household {} invite code rotated (expires {})", saved.getId(), saved.getInviteCodeExpiresAt());
        return respond(saved, user);
    }

    /** Any member may rename; blank clears the name (the app falls back to a default). */
    @Transactional
    public HouseholdResponse rename(User user, String name) {
        var household = loadHouseholdOf(user);
        var trimmed = name == null ? null : name.trim();
        household.setName(trimmed == null || trimmed.isEmpty() ? null : trimmed);
        var saved = householdRepository.save(household);
        log.info("household.renamed {} by {}", saved.getId(), LogMasker.email(user.getEmail()));
        return respond(saved, user);
    }

    /**
     * Only the host may remove someone else. The removed member moves out exactly as if
     * they had left (see {@link #moveOut}).
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
        var home = moveOut(target, household);
        householdCacheGen.bump(household.getId());
        log.info("User {} kicked user {} from household {} (relocated to household {})",
                LogMasker.email(actor.getEmail()), LogMasker.email(target.getEmail()), household.getId(), home.getId());

        var remaining = members.stream().filter(member -> !member.getId().equals(target.getId())).toList();
        notifyMembers(remaining, actor, NotificationType.HOUSEHOLD_MEMBER_LEFT, target.getName());
        return HouseholdResponse.forViewer(household, remaining, actor);
    }

    @Transactional
    public HouseholdResponse getMine(User user) {
        var household = loadHouseholdOf(user);
        return respond(household, user);
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
        if (membersOf(user.getHousehold()).size() > 1) {
            throw new HouseholdLeaveFirstException();
        }
        var currentMembers = membersOf(target);
        requireRoom(target, currentMembers, user);
        returnHomeIfAway(user);
        if (target.getId().equals(user.getHousehold().getId())) {
            return respond(target, user);
        }

        var previous = user.getHousehold();
        user.setHousehold(target);
        user.setHomeHousehold(previous);
        user.setHouseholdJoinedAt(LocalDateTime.now());
        userRepository.save(user);
        expireInviteCode(previous);
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
        return respond(target, user);
    }

    /**
     * Leaving with company: the leaver moves out (see {@link #moveOut}) and the oldest
     * remaining member inherits the host role if needed. Leaving ALONE: a guest whose
     * home is elsewhere returns there taking the whole household with them (it is then
     * deleted); someone alone in their own home has nowhere to go → 400.
     */
    @Transactional
    public HouseholdResponse leave(User user) {
        var previous = user.getHousehold();
        var members = membersOf(previous);
        if (members.size() <= 1) {
            if (!isAwayFromHome(user)) {
                throw new SoloHouseholdLeaveException();
            }
            var home = returnHomeWithEverything(user);
            return respond(home, user);
        }

        var home = moveOut(user, previous);
        householdCacheGen.bump(previous.getId());
        log.info("User {} left household {} for household {}", LogMasker.email(user.getEmail()), previous.getId(), home.getId());

        var remaining = members.stream().filter(member -> !member.getId().equals(user.getId())).toList();
        transferHostIfLeaving(previous, user, remaining);
        deleteHouseholdIfEmptyAndOwnsNoData(previous);

        notifyMembers(remaining, user, NotificationType.HOUSEHOLD_MEMBER_LEFT, user.getName());
        return respond(home, user);
    }

    // ---------- membership moves ----------

    /**
     * Takes {@code user} out of the {@code shared} household, which keeps everything.
     * A guest returns home: what they brought comes back, plus a COPY of what was
     * created since they joined. The original host (it was their home) starts a new
     * household with a COPY of everything, including what guests brought.
     */
    private Household moveOut(User user, Household shared) {
        return isAwayFromHome(user) ? returnGuestHome(user, shared) : startOwnHousehold(user, shared);
    }

    private Household returnGuestHome(User guest, Household shared) {
        var joinedAt = guest.getHouseholdJoinedAt();
        var home = guest.getHomeHousehold();
        settleAtHome(guest, home);
        restoreWhatTheyBrought(guest, home);
        var copied = splitService.copyInto(shared, home, joinedAt);
        log.info("leave.guest_home user={} home={} copied={} since={}",
                LogMasker.email(guest.getEmail()), home.getId(), copied.total(), joinedAt);
        return home;
    }

    private Household startOwnHousehold(User originalHost, Household shared) {
        var fresh = createSoloHousehold();
        settleAtHome(originalHost, fresh);
        var copied = splitService.copyInto(shared, fresh, null);
        log.info("leave.host_new_household user={} household={} copied={}",
                LogMasker.email(originalHost.getEmail()), fresh.getId(), copied.total());
        return fresh;
    }

    // The last member leaves: the household's data moves into their home and the
    // emptied household is deleted.
    private Household returnHomeWithEverything(User lastMember) {
        var shared = lastMember.getHousehold();
        var home = lastMember.getHomeHousehold();
        settleAtHome(lastMember, home);
        restoreWhatTheyBrought(lastMember, home);
        var moved = splitService.absorbInto(shared, home);
        log.info("leave.last_member user={} home={} absorbed={} moved={}",
                LogMasker.email(lastMember.getEmail()), home.getId(), shared.getId(), moved);
        return home;
    }

    // Someone alone in a household that isn't theirs must go home before joining
    // another one, or that household would be stranded with nobody in it.
    private void returnHomeIfAway(User user) {
        if (isAwayFromHome(user)) {
            returnHomeWithEverything(user);
        }
    }

    private void settleAtHome(User user, Household home) {
        user.setHousehold(home);
        user.setHomeHousehold(null);
        user.setHouseholdJoinedAt(null);
        userRepository.save(user);
        if (home.getOwner() == null) {
            home.setOwner(user);
            householdRepository.save(home);
        }
        householdCacheGen.bump(home.getId());
    }

    private void restoreWhatTheyBrought(User user, Household home) {
        if (!mergeEnabled) {
            return;
        }
        var restored = mergeService.restoreOriginals(home);
        log.info("relocate.restore user={} home={} restored={}",
                LogMasker.email(user.getEmail()), home.getId(), restored.moved());
    }

    private static boolean isAwayFromHome(User user) {
        return user.getHomeHousehold() != null
                && !user.getHomeHousehold().getId().equals(user.getHousehold().getId());
    }

    // While its owner is away the household must not take in strangers.
    private void expireInviteCode(Household household) {
        household.setInviteCodeExpiresAt(LocalDateTime.now());
        householdRepository.save(household);
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

    private HouseholdResponse respond(Household household, User viewer) {
        var members = membersOf(household);
        resolveHost(household, members);
        return HouseholdResponse.forViewer(household, members, viewer);
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
