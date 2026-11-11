package com.relyon.economizaai.service;

import com.relyon.economizaai.service.AccountStorageCleaner.StoredObjects;
import com.relyon.economizaai.service.photoattempt.PhotoRetentionService;
import com.relyon.economizaai.service.profile.ProfilePictureStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountStorageCleanerTest {

    @Mock private ProfilePictureStorage profilePictureStorage;
    @Mock private PhotoRetentionService photoRetentionService;

    private final Executor sameThread = Runnable::run;

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private AccountStorageCleaner cleaner(Executor executor) {
        return new AccountStorageCleaner(profilePictureStorage, photoRetentionService, executor);
    }

    @Test
    void removesProfilePictureAndEveryPhotoReceipt() throws IOException {
        var firstReceipt = UUID.randomUUID();
        var secondReceipt = UUID.randomUUID();
        when(photoRetentionService.deleteAttempt(any(UUID.class))).thenReturn(true);

        cleaner(sameThread).deleteAfterCommit(new StoredObjects("pic-key", List.of(firstReceipt, secondReceipt)));

        verify(profilePictureStorage).delete("pic-key");
        verify(photoRetentionService).deleteAttempt(firstReceipt);
        verify(photoRetentionService).deleteAttempt(secondReceipt);
    }

    @Test
    void insideATransactionNothingIsRemovedUntilTheCommit() throws IOException {
        TransactionSynchronizationManager.initSynchronization();

        cleaner(sameThread).deleteAfterCommit(new StoredObjects("pic-key", List.of()));

        verifyNoInteractions(profilePictureStorage);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(profilePictureStorage).delete("pic-key");
    }

    @Test
    void storageFailureIsSwallowedAndTheRestStillRuns() throws IOException {
        var receiptId = UUID.randomUUID();
        doThrow(new IOException("bucket down")).when(profilePictureStorage).delete("pic-key");

        assertThatCode(() -> cleaner(sameThread).deleteAfterCommit(new StoredObjects("pic-key", List.of(receiptId))))
                .doesNotThrowAnyException();

        verify(photoRetentionService).deleteAttempt(receiptId);
    }

    @Test
    void poolRejectionNeverFailsTheCaller() {
        Executor rejecting = task -> {
            throw new RejectedExecutionException("pool full");
        };

        assertThatCode(() -> cleaner(rejecting).deleteAfterCommit(new StoredObjects("pic-key", List.of())))
                .doesNotThrowAnyException();
        verifyNoInteractions(profilePictureStorage);
    }

    @Test
    void accountWithoutStoredObjectsSchedulesNothing() throws IOException {
        cleaner(sameThread).deleteAfterCommit(new StoredObjects(null, List.of()));

        verify(profilePictureStorage, never()).delete(any());
        verifyNoInteractions(photoRetentionService);
    }
}
