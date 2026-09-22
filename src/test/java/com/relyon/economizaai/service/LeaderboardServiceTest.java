package com.relyon.economizaai.service;

import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LeaderboardServiceTest {

    @Mock private ReceiptItemRepository receiptItemRepository;
    @Mock private UserRepository userRepository;
    @InjectMocks private LeaderboardService service;

    private final UUID householdA = UUID.randomUUID();
    private final UUID householdB = UUID.randomUUID();

    private User userIn(UUID householdId, String name, String email, boolean optIn) {
        return User.builder()
                .household(Household.builder().id(householdId).inviteCode("X").build())
                .name(name).email(email).shareInLeaderboard(optIn).build();
    }

    @Test
    void publicViewListsOnlyOptedInAndShowsViewerOwnStanding() {
        when(receiptItemRepository.discountHuntersSince(any())).thenReturn(List.<Object[]>of(
                new Object[]{householdA, 10L, new BigDecimal("50.00")},
                new Object[]{householdB, 8L, new BigDecimal("40.00")}));
        when(userRepository.findByShareInLeaderboardTrue())
                .thenReturn(List.of(userIn(householdA, "Ana Silva", "ana@e", true)));

        // Viewer is household B — has finds but did NOT opt in.
        var response = service.discountHunters(30, householdB);

        assertThat(response.entries()).hasSize(1);
        assertThat(response.entries().get(0).handle()).isEqualTo("Ana");   // first name only
        assertThat(response.entries().get(0).rank()).isEqualTo(1);
        assertThat(response.entries().get(0).isMe()).isFalse();
        // "me" still shows the viewer's own numbers, rank 0 (not in the public list).
        assertThat(response.me().handle()).isEqualTo("Você");
        assertThat(response.me().finds()).isEqualTo(8L);
        assertThat(response.me().rank()).isZero();
    }

    @Test
    void adminViewRanksEveryoneWithEmailsAndNoMe() {
        when(receiptItemRepository.discountHuntersSince(any())).thenReturn(List.<Object[]>of(
                new Object[]{householdA, 10L, new BigDecimal("50.00")},
                new Object[]{householdB, 8L, new BigDecimal("40.00")}));
        when(userRepository.findByHouseholdIdIn(any())).thenReturn(List.of(
                userIn(householdA, "Ana", "ana@e", false),
                userIn(householdB, "Bob", "bob@e", false)));

        var response = service.discountHuntersAdmin(30);

        assertThat(response.entries()).hasSize(2);
        assertThat(response.entries().get(0).handle()).isEqualTo("ana@e");
        assertThat(response.entries().get(0).rank()).isEqualTo(1);
        assertThat(response.entries().get(1).handle()).isEqualTo("bob@e");
        assertThat(response.me()).isNull();
    }

    @Test
    void setOptInPersistsFlag() {
        var user = userIn(householdA, "Ana", "ana@e", false);

        service.setOptIn(user, true);

        assertThat(user.isShareInLeaderboard()).isTrue();
        verify(userRepository).save(user);
    }
}
