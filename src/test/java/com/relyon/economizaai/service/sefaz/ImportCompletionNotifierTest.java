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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImportCompletionNotifierTest {

    @Mock private ReceiptRepository receiptRepository;
    @Mock private UserRepository userRepository;
    @Mock private NotificationService notificationService;
    @Mock private LocalizedMessageService messageService;
    @InjectMocks private ImportCompletionNotifier notifier;

    private final UUID userId = UUID.randomUUID();

    @Test
    void notifiesWhenBatchDoneAndSomethingToReport() {
        var user = User.builder().id(userId).email("test123@economizaai.app").locale("pt").build();
        when(receiptRepository.countByUserIdAndOriginAndStatusIn(eq(userId), eq(ReceiptOrigin.IMPORT), any()))
                .thenReturn(0L);
        when(receiptRepository.countByUserIdAndOriginAndStatus(userId, ReceiptOrigin.IMPORT, ReceiptStatus.PENDING_CONFIRMATION))
                .thenReturn(7L);
        when(receiptRepository.countByUserIdAndOriginAndStatus(userId, ReceiptOrigin.IMPORT, ReceiptStatus.FAILED_PARSE))
                .thenReturn(2L);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        notifier.notifyIfBatchComplete(userId);

        var payload = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationService).notify(payload.capture());
        assertThat(payload.getValue().type()).isEqualTo(NotificationType.SYSTEM);
        assertThat(payload.getValue().user()).isEqualTo(user);
    }

    @Test
    void doesNotNotifyWhileNotasStillInFlight() {
        when(receiptRepository.countByUserIdAndOriginAndStatusIn(eq(userId), eq(ReceiptOrigin.IMPORT), any()))
                .thenReturn(3L);

        notifier.notifyIfBatchComplete(userId);

        verify(notificationService, never()).notify(any());
    }

    @Test
    void doesNotNotifyWhenNothingLeftToReport() {
        when(receiptRepository.countByUserIdAndOriginAndStatusIn(eq(userId), eq(ReceiptOrigin.IMPORT), any()))
                .thenReturn(0L);
        when(receiptRepository.countByUserIdAndOriginAndStatus(userId, ReceiptOrigin.IMPORT, ReceiptStatus.PENDING_CONFIRMATION))
                .thenReturn(0L);
        when(receiptRepository.countByUserIdAndOriginAndStatus(userId, ReceiptOrigin.IMPORT, ReceiptStatus.FAILED_PARSE))
                .thenReturn(0L);

        notifier.notifyIfBatchComplete(userId);

        verify(notificationService, never()).notify(any());
    }

    @Test
    void ignoresNullUserId() {
        notifier.notifyIfBatchComplete(null);

        verify(receiptRepository, never()).countByUserIdAndOriginAndStatusIn(any(), any(), any());
        verify(notificationService, never()).notify(any());
    }
}
