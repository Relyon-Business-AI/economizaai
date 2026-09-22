package com.relyon.economizaai.service;

import com.relyon.economizaai.dto.request.JoinHouseholdRequest;
import com.relyon.economizaai.exception.AlreadyInHouseholdException;
import com.relyon.economizaai.exception.InvalidInviteCodeException;
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

@ExtendWith(MockitoExtension.class)
class HouseholdServiceTest {

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

    private Household buildHousehold(String code) {
        var h = Household.builder().id(UUID.randomUUID()).inviteCode(code).build();
        h.setCreatedAt(LocalDateTime.now());
        h.setUpdatedAt(LocalDateTime.now());
        return h;
    }

    @Test
    void createSoloHousehold_persistsWithGeneratedInviteCode() {
        when(householdRepository.existsByInviteCode(any())).thenReturn(false);
        when(householdRepository.save(any(Household.class))).thenAnswer(inv -> {
            var h = inv.<Household>getArgument(0);
            h.setId(UUID.randomUUID());
            return h;
        });

        var saved = householdService.createSoloHousehold();

        assertNotNull(saved.getInviteCode());
        assertEquals(6, saved.getInviteCode().length());
    }

    @Test
    void getMine_returnsHouseholdWithMembers() {
        var household = buildHousehold("ABC123");
        var user = buildUser(household);
        when(householdRepository.findById(household.getId())).thenReturn(Optional.of(household));
        when(userRepository.findAllByHouseholdId(household.getId())).thenReturn(List.of(user));

        var response = householdService.getMine(user);

        assertEquals(household.getId(), response.id());
        assertEquals("ABC123", response.inviteCode());
        assertEquals(1, response.members().size());
        assertEquals("john@test.com", response.members().get(0).email());
    }

    @Test
    void join_movesUserToTargetHouseholdAndDeletesEmptyPrevious() {
        var previous = buildHousehold("OLD123");
        var target = buildHousehold("NEW456");
        var user = buildUser(previous);

        when(householdRepository.findByInviteCode("NEW456")).thenReturn(Optional.of(target));
        when(userRepository.countByHouseholdId(previous.getId())).thenReturn(0L);
        when(receiptRepository.existsByHouseholdId(previous.getId())).thenReturn(false);
        when(receiptRepository.existsByOriginHouseholdId(previous.getId())).thenReturn(false);
        when(userRepository.findAllByHouseholdId(target.getId())).thenReturn(List.of(user));

        var response = householdService.join(user, new JoinHouseholdRequest("new456", null, null));

        assertEquals(target.getId(), user.getHousehold().getId());
        assertEquals(target.getId(), response.id());
        verify(householdRepository).delete(previous);
    }

    @Test
    void join_keepsEmptyPreviousHouseholdWhenItStillOwnsData() {
        var previous = buildHousehold("OLD123");
        var target = buildHousehold("NEW456");
        var user = buildUser(previous);

        when(householdRepository.findByInviteCode("NEW456")).thenReturn(Optional.of(target));
        when(userRepository.countByHouseholdId(previous.getId())).thenReturn(0L);
        // no members, but data (receipts) still reference it as origin -> must NOT delete
        when(receiptRepository.existsByHouseholdId(previous.getId())).thenReturn(false);
        when(receiptRepository.existsByOriginHouseholdId(previous.getId())).thenReturn(true);
        when(userRepository.findAllByHouseholdId(target.getId())).thenReturn(List.of(user));

        householdService.join(user, new JoinHouseholdRequest("NEW456", null, null));

        verify(householdRepository, never()).delete(previous);
    }

    @Test
    void join_keepsPreviousHouseholdWhenStillHasMembers() {
        var previous = buildHousehold("OLD123");
        var target = buildHousehold("NEW456");
        var user = buildUser(previous);

        when(householdRepository.findByInviteCode("NEW456")).thenReturn(Optional.of(target));
        when(userRepository.countByHouseholdId(previous.getId())).thenReturn(2L);
        when(userRepository.findAllByHouseholdId(target.getId())).thenReturn(List.of(user));

        householdService.join(user, new JoinHouseholdRequest("NEW456", null, null));

        verify(householdRepository, never()).delete(previous);
    }

    @Test
    void join_throwsForInvalidCode() {
        var household = buildHousehold("ABC123");
        var user = buildUser(household);
        when(householdRepository.findByInviteCode("XYZ999")).thenReturn(Optional.empty());
        var request = new JoinHouseholdRequest("XYZ999", null, null);

        assertThrows(InvalidInviteCodeException.class,
                () -> householdService.join(user, request));
    }

    @Test
    void join_throwsWhenAlreadyInTargetHousehold() {
        var household = buildHousehold("ABC123");
        var user = buildUser(household);
        when(householdRepository.findByInviteCode("ABC123")).thenReturn(Optional.of(household));
        var request = new JoinHouseholdRequest("ABC123", null, null);

        assertThrows(AlreadyInHouseholdException.class,
                () -> householdService.join(user, request));
    }

    @Test
    void leave_movesUserToFreshSoloHouseholdAndDeletesEmptyPrevious() {
        var previous = buildHousehold("OLD123");
        var user = buildUser(previous);

        when(householdRepository.existsByInviteCode(any())).thenReturn(false);
        when(householdRepository.save(any(Household.class))).thenAnswer(inv -> {
            var h = inv.<Household>getArgument(0);
            h.setId(UUID.randomUUID());
            h.setCreatedAt(LocalDateTime.now());
            return h;
        });
        when(userRepository.countByHouseholdId(previous.getId())).thenReturn(0L);
        when(receiptRepository.existsByHouseholdId(previous.getId())).thenReturn(false);
        when(receiptRepository.existsByOriginHouseholdId(previous.getId())).thenReturn(false);
        when(userRepository.findAllByHouseholdId(any())).thenReturn(List.of(user));

        var response = householdService.leave(user);

        assertNotEquals(previous.getId(), user.getHousehold().getId());
        assertNotNull(response.inviteCode());
        verify(householdRepository).delete(previous);
    }

    @Test
    void leave_keepsEmptyPreviousHouseholdWhenItStillOwnsData() {
        var previous = buildHousehold("OLD123");
        var user = buildUser(previous);

        when(householdRepository.existsByInviteCode(any())).thenReturn(false);
        when(householdRepository.save(any(Household.class))).thenAnswer(inv -> {
            var h = inv.<Household>getArgument(0);
            h.setId(UUID.randomUUID());
            h.setCreatedAt(LocalDateTime.now());
            return h;
        });
        when(userRepository.countByHouseholdId(previous.getId())).thenReturn(0L);
        when(receiptRepository.existsByHouseholdId(previous.getId())).thenReturn(true);
        when(userRepository.findAllByHouseholdId(any())).thenReturn(List.of(user));

        householdService.leave(user);

        verify(householdRepository, never()).delete(previous);
    }
}
