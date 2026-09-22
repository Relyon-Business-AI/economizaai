package com.relyon.economizaai.service;

import com.relyon.economizaai.dto.request.JoinHouseholdRequest;
import com.relyon.economizaai.exception.InvalidInviteCodeException;
import com.relyon.economizaai.exception.NotInHouseholdException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.HouseholdRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the HouseholdService branches not exercised by {@link HouseholdServiceTest}:
 * getMine/regenerate not-found, removeMember (all paths), expired invite code,
 * and the regenerate happy path.
 */
@ExtendWith(MockitoExtension.class)
class HouseholdServiceCoverageTest {

    @Mock
    private HouseholdRepository householdRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ReceiptRepository receiptRepository;

    @Mock
    private HouseholdMergeService mergeService;

    @InjectMocks
    private HouseholdService householdService;

    private User buildUser(Household household) {
        var user = User.builder()
                .id(UUID.randomUUID())
                .name("John")
                .email("john@test.com")
                .password("encoded")
                .household(household)
                .build();
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        return user;
    }

    private Household buildHousehold(String inviteCode) {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode(inviteCode).build();
        household.setCreatedAt(LocalDateTime.now());
        household.setUpdatedAt(LocalDateTime.now());
        return household;
    }

    // ---------- getMine ----------

    @Test
    void getMine_throwsWhenHouseholdMissing() {
        var household = buildHousehold("ABC123");
        var user = buildUser(household);
        when(householdRepository.findById(household.getId())).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> householdService.getMine(user));
    }

    // ---------- regenerateInviteCode ----------

    @Test
    void regenerateInviteCode_rotatesCodeAndReturnsMembers() {
        var household = buildHousehold("OLD999");
        var user = buildUser(household);
        when(householdRepository.findById(household.getId())).thenReturn(Optional.of(household));
        when(householdRepository.existsByInviteCode(any())).thenReturn(false);
        when(householdRepository.save(any(Household.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.findAllByHouseholdId(household.getId())).thenReturn(List.of(user));

        var response = householdService.regenerateInviteCode(user);

        assertNotNull(response.inviteCode());
        assertEquals(6, response.inviteCode().length());
        assertNotEquals("OLD999", response.inviteCode());
        assertNotNull(household.getInviteCodeExpiresAt());
        verify(householdRepository).save(household);
    }

    @Test
    void regenerateInviteCode_throwsWhenHouseholdMissing() {
        var household = buildHousehold("ABC123");
        var user = buildUser(household);
        when(householdRepository.findById(household.getId())).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> householdService.regenerateInviteCode(user));
    }

    // ---------- removeMember ----------

    @Test
    void removeMember_movesTargetToFreshSoloHousehold() {
        var household = buildHousehold("FAM111");
        var actor = buildUser(household);
        var target = buildUser(household);
        when(householdRepository.findById(household.getId())).thenReturn(Optional.of(household));
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(householdRepository.existsByInviteCode(any())).thenReturn(false);
        when(householdRepository.save(any(Household.class))).thenAnswer(invocation -> {
            var saved = invocation.<Household>getArgument(0);
            saved.setId(UUID.randomUUID());
            saved.setCreatedAt(LocalDateTime.now());
            return saved;
        });
        when(userRepository.findAllByHouseholdId(household.getId())).thenReturn(List.of(actor));

        var response = householdService.removeMember(actor, target.getId());

        assertNotEquals(household.getId(), target.getHousehold().getId());
        assertEquals(household.getId(), response.id());
        verify(userRepository).save(target);
    }

    @Test
    void removeMember_throwsWhenActorTriesToKickThemselves() {
        var household = buildHousehold("FAM111");
        var actor = buildUser(household);
        when(householdRepository.findById(household.getId())).thenReturn(Optional.of(household));
        var actorId = actor.getId();

        assertThrows(IllegalArgumentException.class, () -> householdService.removeMember(actor, actorId));
        verify(userRepository, never()).save(any());
    }

    @Test
    void removeMember_throwsWhenActorHouseholdMissing() {
        var household = buildHousehold("FAM111");
        var actor = buildUser(household);
        when(householdRepository.findById(household.getId())).thenReturn(Optional.empty());
        var memberId = UUID.randomUUID();

        assertThrows(IllegalStateException.class,
                () -> householdService.removeMember(actor, memberId));
    }

    @Test
    void removeMember_throwsWhenTargetNotFound() {
        var household = buildHousehold("FAM111");
        var actor = buildUser(household);
        var memberId = UUID.randomUUID();
        when(householdRepository.findById(household.getId())).thenReturn(Optional.of(household));
        when(userRepository.findById(memberId)).thenReturn(Optional.empty());

        assertThrows(NotInHouseholdException.class, () -> householdService.removeMember(actor, memberId));
    }

    @Test
    void removeMember_throwsWhenTargetInDifferentHousehold() {
        var actorHousehold = buildHousehold("FAM111");
        var otherHousehold = buildHousehold("OTHER2");
        var actor = buildUser(actorHousehold);
        var target = buildUser(otherHousehold);
        when(householdRepository.findById(actorHousehold.getId())).thenReturn(Optional.of(actorHousehold));
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        var targetId = target.getId();

        assertThrows(NotInHouseholdException.class, () -> householdService.removeMember(actor, targetId));
        verify(userRepository, never()).save(any());
    }

    // ---------- join: expired code ----------

    @Test
    void join_throwsWhenInviteCodeExpired() {
        var current = buildHousehold("CUR123");
        var target = buildHousehold("EXP456");
        target.setInviteCodeExpiresAt(LocalDateTime.now().minusHours(1));
        var user = buildUser(current);
        when(householdRepository.findByInviteCode("EXP456")).thenReturn(Optional.of(target));
        var request = new JoinHouseholdRequest("exp456", null, null);

        assertThrows(InvalidInviteCodeException.class,
                () -> householdService.join(user, request));
        verify(userRepository, never()).save(any());
    }
}
