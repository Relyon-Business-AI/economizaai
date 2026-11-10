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

/**
 * Household membership — the rules (2026-11):
 *
 * <ul>
 *   <li><b>Host</b> (anfitrião): the creator, or the guest promoted when the host left. Only
 *       the host rotates the invite code and removes members.</li>
 *   <li><b>Guest</b>: joined by invite. Each user carries their ORIGIN household and the
 *       instant they JOINED the current one — nothing is deduced from receipts.</li>
 *   <li><b>Join</b>: the guest may bring their data (moved from their origin) or leave it
 *       parked there, intact.</li>
 *   <li><b>Guest leaves / is removed</b>: back to the origin household with everything that
 *       was there, plus what they brought (restored), plus a COPY of what the shared
 *       household generated after they joined. Those who stay lose nothing.</li>
 *   <li><b>Original host leaves</b>: a new household with a copy of everything; the oldest
 *       guest becomes host and the household goes on with everyone else.</li>
 *   <li><b>Promoted host leaves</b>: back to their origin like a guest.</li>
 *   <li><b>Last member leaves</b>: the data MOVES to their origin (nobody stays), duplicates
 *       are dropped, the empty household is deleted.</li>
 *   <li>A host alone in their own household has nowhere to go: leave is refused.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HouseholdService {

    private static final String INVITE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int INVITE_LENGTH = 6;
    private static final int MAX_INVITE_ATTEMPTS = 10;
    private static final int INVITE_TTL_HOURS = 48;
    private static final String HOUSEHOLD_MISSING_MSG = "Household missing for user ";

    // "Oldest guest" = who has been in the household longest; account age breaks ties
    // (and covers legacy rows backfilled without a join instant).
    private static final Comparator<User> BY_SENIORITY =
            Comparator.comparing(User::getHouseholdJoinedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(User::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
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

    // ---------------------------------------------------------------- creation / host

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

    /**
     * Make {@code user} the host of the household created for them (registration): it
     * becomes their origin — the place they return to — and their membership starts now.
     */
    @Transactional
    public void assignHost(Household household, User user) {
        household.setOwner(user);
        householdRepository.save(household);
        user.setOriginHousehold(household);
        user.setHouseholdJoinedAt(LocalDateTime.now());
        userRepository.save(user);
    }

    /** Host only: a new code invalidates the old one on the spot. */
    @Transactional
    public HouseholdResponse regenerateInviteCode(User user) {
        var household = loadHouseholdOf(user);
        requireHost(user, household, membersOf(household));
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

    @Transactional
    public HouseholdResponse getMine(User user) {
        return respond(loadHouseholdOf(user));
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

    // ---------------------------------------------------------------- join

    /**
     * Enter another household. If the user was sharing a house (or sitting alone in one
     * that isn't theirs) they first depart it under the usual rules, landing at their
     * origin; then membership moves and, when {@code bringData} isn't false, their origin's
     * data is merged into the target. Their origin household stays alive either way.
     */
    @Transactional
    public HouseholdResponse join(User user, JoinHouseholdRequest request) {
        var target = findValidByInviteCode(request.inviteCode());
        if (target.getId().equals(user.getHousehold().getId())) {
            throw new AlreadyInHouseholdException();
        }
        var currentMembers = membersOf(target);
        requireRoom(target, currentMembers, user);

        var home = settleAtHomeBeforeJoining(user);

        user.setHousehold(target);
        user.setHouseholdJoinedAt(LocalDateTime.now());
        userRepository.save(user);
        log.info("User {} joined household {} (from {})", LogMasker.email(user.getEmail()), target.getId(), home.getId());

        var bringData = !Boolean.FALSE.equals(request.bringData());
        if (bringData) {
            var categories = resolveCategories(request.mergeCategories());
            var result = mergeService.merge(home, target, categories);
            log.info("join.merge user={} categories={} moved={} shadowed={}",
                    LogMasker.email(user.getEmail()), categories, result.moved(), result.shadowed());
        } else {
            log.info("join.keep_data_at_origin user={} origin={}", LogMasker.email(user.getEmail()), home.getId());
        }
        householdCacheGen.bump(home.getId());
        householdCacheGen.bump(target.getId());

        notifyMembers(currentMembers, user, NotificationType.HOUSEHOLD_MEMBER_JOINED, user.getName());
        return respond(target);
    }

    // Where the joiner's data lives right before the merge: their own household. A solo
    // host is already home; anyone else departs their current house first.
    private Household settleAtHomeBeforeJoining(User user) {
        var current = user.getHousehold();
        var members = membersOf(current);
        if (members.size() <= 1 && isOrigin(user, current)) {
            return current;
        }
        var home = departCurrent(user, current, members);
        var remaining = members.stream().filter(member -> !member.getId().equals(user.getId())).toList();
        notifyMembers(remaining, user, NotificationType.HOUSEHOLD_MEMBER_LEFT, user.getName());
        return home;
    }

    // ---------------------------------------------------------------- leave / remove

    @Transactional
    public HouseholdResponse leave(User user) {
        var current = user.getHousehold();
        var members = membersOf(current);
        if (members.size() <= 1 && isOrigin(user, current)) {
            throw new SoloHouseholdLeaveException();
        }
        var home = departCurrent(user, current, members);
        log.info("User {} left household {} for household {}", LogMasker.email(user.getEmail()), current.getId(), home.getId());

        var remaining = members.stream().filter(member -> !member.getId().equals(user.getId())).toList();
        notifyMembers(remaining, user, NotificationType.HOUSEHOLD_MEMBER_LEFT, user.getName());
        return HouseholdResponse.from(home, List.of(user));
    }

    /** Host only. The removed member leaves exactly as if they had chosen to. */
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
        var home = departAsGuest(target, household, members);
        log.info("User {} kicked user {} from household {} (relocated to household {})",
                LogMasker.email(actor.getEmail()), LogMasker.email(target.getEmail()), household.getId(), home.getId());

        var remaining = members.stream().filter(member -> !member.getId().equals(target.getId())).toList();
        notifyMembers(remaining, actor, NotificationType.HOUSEHOLD_MEMBER_LEFT, target.getName());
        return HouseholdResponse.from(household, remaining);
    }

    // ---------------------------------------------------------------- departure engine

    /** Applies the departure rules for {@code user} leaving {@code current}; returns where they live now. */
    private Household departCurrent(User user, Household current, List<User> members) {
        if (members.size() <= 1) {
            return departAsLastMember(user, current);
        }
        if (isHostOf(user, current) && isOrigin(user, current)) {
            return departAsOriginalHost(user, current, members);
        }
        return departAsGuest(user, current, members);
    }

    /**
     * Guest (or promoted host) leaving: back to origin with everything that was there,
     * what they brought (restored) and a copy of what was generated since they joined.
     * If they hosted, the oldest remaining guest inherits.
     */
    private Household departAsGuest(User user, Household current, List<User> members) {
        var joinedAt = user.getHouseholdJoinedAt();
        var home = returnHome(user, current);
        moveUser(user, home);
        var restored = mergeService.restoreOriginals(home);
        var copied = mergeService.copyGeneratedSince(current, home, joinedAt);
        log.info("depart.guest user={} from={} home={} restored={} copied={}",
                LogMasker.email(user.getEmail()), current.getId(), home.getId(), restored.moved(), copied);
        if (isHostOf(user, current)) {
            transferHost(current, remainingWithout(members, user));
        }
        bumpBoth(current, home);
        return home;
    }

    /** Original host leaving a shared household: fresh household with a copy of everything; oldest guest inherits. */
    private Household departAsOriginalHost(User user, Household current, List<User> members) {
        var home = createSoloHouseholdFor(user);
        moveUser(user, home);
        var copied = mergeService.copyGeneratedSince(current, home, null);
        log.info("depart.original_host user={} from={} home={} copied={}",
                LogMasker.email(user.getEmail()), current.getId(), home.getId(), copied);
        transferHost(current, remainingWithout(members, user));
        bumpBoth(current, home);
        return home;
    }

    /** Nobody stays: the data moves home with the leaver, duplicates are dropped, the household is deleted. */
    private Household departAsLastMember(User user, Household current) {
        var home = returnHome(user, current);
        moveUser(user, home);
        var result = mergeService.evacuate(current, home);
        log.info("depart.last_member user={} from={} home={} moved={} dropped={}",
                LogMasker.email(user.getEmail()), current.getId(), home.getId(), result.moved(), result.shadowed());
        deleteHouseholdIfOrphan(current);
        householdCacheGen.bump(home.getId());
        return home;
    }

    // The leaver's own household — their recorded origin when it still exists (and isn't
    // the one being left); otherwise a brand-new one that becomes their origin.
    private Household returnHome(User user, Household current) {
        var origin = user.getOriginHousehold();
        if (origin != null && !origin.getId().equals(current.getId())) {
            var alive = householdRepository.findById(origin.getId()).orElse(null);
            if (alive != null) {
                if (alive.getOwner() == null) {
                    alive.setOwner(user);
                    alive = householdRepository.save(alive);
                }
                return alive;
            }
        }
        return createSoloHouseholdFor(user);
    }

    private Household createSoloHouseholdFor(User user) {
        var fresh = createSoloHousehold();
        fresh.setOwner(user);
        fresh = householdRepository.save(fresh);
        user.setOriginHousehold(fresh);
        return fresh;
    }

    private void moveUser(User user, Household home) {
        user.setHousehold(home);
        user.setHouseholdJoinedAt(LocalDateTime.now());
        if (user.getOriginHousehold() == null) {
            user.setOriginHousehold(home);
        }
        userRepository.save(user);
    }

    private void bumpBoth(Household first, Household second) {
        householdCacheGen.bump(first.getId());
        householdCacheGen.bump(second.getId());
    }

    private static List<User> remainingWithout(List<User> members, User leaver) {
        return members.stream().filter(member -> !member.getId().equals(leaver.getId())).toList();
    }

    // A household with no members, no data and nobody calling it home has no reason to exist.
    private void deleteHouseholdIfOrphan(Household household) {
        if (userRepository.countByHouseholdId(household.getId()) != 0) {
            return;
        }
        if (userRepository.existsByOriginHouseholdId(household.getId())) {
            log.info("household.keep_origin {} has no members but is someone's origin; not deleting", household.getId());
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

    // ---------------------------------------------------------------- host (anfitrião)

    private boolean isHostOf(User user, Household household) {
        var owner = household.getOwner();
        return owner != null && owner.getId().equals(user.getId());
    }

    private static boolean isOrigin(User user, Household household) {
        var origin = user.getOriginHousehold();
        return origin != null && origin.getId().equals(household.getId());
    }

    private void requireHost(User actor, Household household, List<User> members) {
        var host = resolveHost(household, members);
        if (host == null || !host.getId().equals(actor.getId())) {
            throw new NotHouseholdHostException();
        }
    }

    /**
     * The host, self-healing a missing or stale one (account deleted, legacy row) to the
     * longest-standing current member. Null only for an empty household.
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
        return transferHost(household, members);
    }

    /** The oldest guest becomes host (rule 14). Returns the new host, or null when nobody is left. */
    private User transferHost(Household household, List<User> remaining) {
        var heir = remaining.stream().min(BY_SENIORITY).orElse(null);
        if (heir != null) {
            household.setOwner(heir);
            householdRepository.save(household);
            log.info("household.host_assigned household={} host={}", household.getId(), LogMasker.email(heir.getEmail()));
        }
        return heir;
    }

    // ---------------------------------------------------------------- capacity

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

    // ---------------------------------------------------------------- notifications

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

    // ---------------------------------------------------------------- helpers

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
