package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.model.enums.ReceiptOrigin;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.notifications.NotificationPayload;
import com.relyon.economizaai.service.notifications.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImportCompletionNotifierTest {

    @Mock private ReceiptRepository receiptRepository;
    @Mock private UserRepository userRepository;
    @Mock private NotificationService notificationService;
    @Mock private LocalizedMessageService messageService;
    @Mock private TransactionTemplate transactionTemplate;
    @InjectMocks private ImportCompletionNotifier notifier;

    private final UUID userId = UUID.randomUUID();
    private final LocalDateTime newestTerminalAt = LocalDateTime.now();
    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id(userId).email("test123@economizaai.app").locale("pt").build();
        // The claim block runs inside a TransactionTemplate — execute it inline.
        lenient().when(transactionTemplate.execute(any()))
                .thenAnswer(invocation -> invocation.<TransactionCallback<Object>>getArgument(0).doInTransaction(null));
    }

    private void stubCompletedBatch(long ready, long failed) {
        when(receiptRepository.countByUserIdAndOriginAndStatusIn(eq(userId), eq(ReceiptOrigin.IMPORT), any()))
                .thenReturn(0L);
        when(receiptRepository.latestTerminalImportUpdatedAt(userId)).thenReturn(newestTerminalAt);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        lenient().when(receiptRepository.countByUserIdAndOriginAndStatus(
                userId, ReceiptOrigin.IMPORT, ReceiptStatus.PENDING_CONFIRMATION)).thenReturn(ready);
        lenient().when(receiptRepository.countByUserIdAndOriginAndStatus(
                userId, ReceiptOrigin.IMPORT, ReceiptStatus.FAILED_PARSE)).thenReturn(failed);
    }

    @Test
    void sweep_notifiesUserWhoseBatchJustFinished_andStampsTheClaim() {
        when(receiptRepository.findUsersWithUnnotifiedImportResults()).thenReturn(List.of(userId));
        stubCompletedBatch(7, 2);

        notifier.notifyCompletedBatches();

        var payload = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService).notify(payload.capture());
        assertThat(payload.getValue().type()).isEqualTo(NotificationType.SYSTEM);
        assertThat(payload.getValue().user()).isEqualTo(user);
        assertThat(user.getImportCompletionNotifiedAt()).isEqualTo(newestTerminalAt);
        verify(userRepository).save(user);
    }

    @Test
    void sweep_staysSilentWhileNotasStillInFlight() {
        when(receiptRepository.findUsersWithUnnotifiedImportResults()).thenReturn(List.of(userId));
        when(receiptRepository.countByUserIdAndOriginAndStatusIn(eq(userId), eq(ReceiptOrigin.IMPORT), any()))
                .thenReturn(3L);

        notifier.notifyCompletedBatches();

        verify(notificationService, never()).notify(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void sweep_alreadyNotifiedWave_neverDuplicates() {
        // The claim equals the newest terminal row → this wave was announced already.
        user.setImportCompletionNotifiedAt(newestTerminalAt);
        when(receiptRepository.findUsersWithUnnotifiedImportResults()).thenReturn(List.of(userId));
        when(receiptRepository.countByUserIdAndOriginAndStatusIn(eq(userId), eq(ReceiptOrigin.IMPORT), any()))
                .thenReturn(0L);
        when(receiptRepository.latestTerminalImportUpdatedAt(userId)).thenReturn(newestTerminalAt);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        notifier.notifyCompletedBatches();

        verify(notificationService, never()).notify(any());
    }

    @Test
    void sweep_newWaveAfterEarlierClaim_notifiesAgain() {
        user.setImportCompletionNotifiedAt(newestTerminalAt.minusHours(2));
        when(receiptRepository.findUsersWithUnnotifiedImportResults()).thenReturn(List.of(userId));
        stubCompletedBatch(3, 0);

        notifier.notifyCompletedBatches();

        verify(notificationService).notify(any());
        assertThat(user.getImportCompletionNotifiedAt()).isEqualTo(newestTerminalAt);
    }

    @Test
    void sweep_consecutiveTicksOnTheSameFinishedWave_notifyExactlyOnce() {
        // Tick 1 announces and stamps the claim; tick 2 re-checks and stays silent.
        when(receiptRepository.findUsersWithUnnotifiedImportResults()).thenReturn(List.of(userId));
        stubCompletedBatch(5, 1);

        notifier.notifyCompletedBatches();
        notifier.notifyCompletedBatches();

        verify(notificationService, times(1)).notify(any());
    }

    @Test
    void sweep_nothingLeftToReport_staysSilent() {
        // All terminal rows confirmed/deleted meanwhile — MAX(updatedAt) is null.
        when(receiptRepository.findUsersWithUnnotifiedImportResults()).thenReturn(List.of(userId));
        when(receiptRepository.countByUserIdAndOriginAndStatusIn(eq(userId), eq(ReceiptOrigin.IMPORT), any()))
                .thenReturn(0L);
        when(receiptRepository.latestTerminalImportUpdatedAt(userId)).thenReturn(null);

        notifier.notifyCompletedBatches();

        verify(notificationService, never()).notify(any());
    }

    @Test
    void notifyIfBatchComplete_ignoresNullUserId() {
        notifier.notifyIfBatchComplete(null);

        verify(transactionTemplate, never()).execute(any());
        verify(notificationService, never()).notify(any());
    }
}
