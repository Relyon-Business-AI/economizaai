package com.relyon.economizaai.service;

import com.relyon.economizaai.dto.response.LeaderboardResponse;
import com.relyon.economizaai.dto.response.LeaderboardResponse.Entry;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * "Caçador de descontos" leaderboard — ranks households by how many items they bought
 * below the community average price (the {@code discountHuntersSince} query). Public
 * view shows only households that opted in ({@code shareInLeaderboard}); the caller
 * always sees their own standing. Admin view shows everyone with emails.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeaderboardService {

    private static final int TOP_N = 20;

    private final ReceiptItemRepository receiptItemRepository;
    private final UserRepository userRepository;

    /** Public leaderboard: opted-in households only, plus the caller's own standing. */
    @Transactional(readOnly = true)
    public LeaderboardResponse discountHunters(int days, UUID viewerHouseholdId) {
        var window = Math.max(1, days);
        var ranked = ranked(window);
        var optedInUsers = userRepository.findByShareInLeaderboardTrue();
        var optedInHouseholds = optedInUsers.stream()
                .map(this::householdId).filter(Objects::nonNull).collect(Collectors.toSet());
        var handleByHousehold = firstNameByHousehold(optedInUsers);

        var publicRows = ranked.stream()
                .filter(row -> optedInHouseholds.contains(row.householdId()))
                .limit(TOP_N).toList();
        var entries = new ArrayList<Entry>();
        var rank = 0;
        for (var row : publicRows) {
            rank++;
            entries.add(new Entry(rank, handleByHousehold.getOrDefault(row.householdId(), "Caçador"),
                    row.finds(), row.savings(), row.householdId().equals(viewerHouseholdId)));
        }
        return new LeaderboardResponse(window, entries, buildMe(ranked, publicRows, viewerHouseholdId));
    }

    /** Admin leaderboard: all households (opted in or not), handles are emails. */
    @Transactional(readOnly = true)
    public LeaderboardResponse discountHuntersAdmin(int days) {
        var window = Math.max(1, days);
        var top = ranked(window).stream().limit(TOP_N).toList();
        var emailByHousehold = emailByHousehold(top.stream().map(Row::householdId).toList());
        var entries = new ArrayList<Entry>();
        var rank = 0;
        for (var row : top) {
            rank++;
            entries.add(new Entry(rank, emailByHousehold.getOrDefault(row.householdId(), "—"),
                    row.finds(), row.savings(), false));
        }
        return new LeaderboardResponse(window, entries, null);
    }

    /** Toggle the caller's opt-in to the public leaderboard. */
    @Transactional
    public void setOptIn(User user, boolean optIn) {
        user.setShareInLeaderboard(optIn);
        userRepository.save(user);
        log.info("leaderboard.opt_in user={} optIn={}", user.getId(), optIn);
    }

    private Entry buildMe(List<Row> ranked, List<Row> publicRows, UUID viewerHouseholdId) {
        if (viewerHouseholdId == null) {
            return null;
        }
        var mine = ranked.stream().filter(row -> viewerHouseholdId.equals(row.householdId())).findFirst();
        var finds = mine.map(Row::finds).orElse(0L);
        var savings = mine.map(Row::savings).orElse(BigDecimal.ZERO.setScale(2));
        var publicRank = 0;
        for (var index = 0; index < publicRows.size(); index++) {
            if (viewerHouseholdId.equals(publicRows.get(index).householdId())) {
                publicRank = index + 1;
                break;
            }
        }
        return new Entry(publicRank, "Você", finds, savings, true);
    }

    private List<Row> ranked(int windowDays) {
        var since = LocalDate.now().minusDays(windowDays - 1L).atStartOfDay();
        var rows = new ArrayList<Row>();
        for (var raw : receiptItemRepository.discountHuntersSince(since)) {
            rows.add(new Row(toUuid(raw[0]), ((Number) raw[1]).longValue(), scale((BigDecimal) raw[2])));
        }
        return rows;
    }

    /** householdId -> first name (privacy-safe handle) for opted-in members. */
    private Map<UUID, String> firstNameByHousehold(List<User> optedInUsers) {
        var handles = new HashMap<UUID, String>();
        for (var user : optedInUsers) {
            var household = householdId(user);
            if (household != null) {
                handles.putIfAbsent(household, firstName(user.getName()));
            }
        }
        return handles;
    }

    private Map<UUID, String> emailByHousehold(List<UUID> householdIds) {
        var emails = new HashMap<UUID, String>();
        if (householdIds.isEmpty()) {
            return emails;
        }
        for (var user : userRepository.findByHouseholdIdIn(Set.copyOf(householdIds))) {
            var household = householdId(user);
            if (household != null) {
                emails.putIfAbsent(household, user.getEmail());
            }
        }
        return emails;
    }

    private UUID householdId(User user) {
        return user.getHousehold() == null ? null : user.getHousehold().getId();
    }

    private static String firstName(String name) {
        if (name == null || name.isBlank()) {
            return "Caçador";
        }
        return name.trim().split("\\s+")[0];
    }

    private static UUID toUuid(Object value) {
        return value instanceof UUID uuid ? uuid : UUID.fromString(value.toString());
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2, RoundingMode.HALF_UP);
    }

    private record Row(UUID householdId, long finds, BigDecimal savings) {
    }
}
