package com.relyon.economizaai.service;

import com.relyon.economizaai.config.AsyncConfig;
import com.relyon.economizaai.service.photoattempt.PhotoRetentionService;
import com.relyon.economizaai.service.profile.ProfilePictureStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Removes what a deleted account left in object storage: the profile picture and the
 * photos kept for its photo-extracted receipts.
 *
 * <p>Storage deletion is an outbound call, so it never runs inside the account-deletion
 * transaction: it is scheduled for AFTER the commit and handed to the async pool (the
 * commit thread still holds the database connection). It is best-effort by design — the
 * account is already gone, so a storage failure is logged and never surfaces to the user.
 * The bucket lifecycle rule is the safety net for anything missed here.
 */
@Slf4j
@Component
public class AccountStorageCleaner {

    private final ProfilePictureStorage profilePictureStorage;
    private final PhotoRetentionService photoRetentionService;
    private final Executor executor;

    public AccountStorageCleaner(ProfilePictureStorage profilePictureStorage,
                                 PhotoRetentionService photoRetentionService,
                                 @Qualifier(AsyncConfig.RECEIPT_INGEST_EXECUTOR) Executor executor) {
        this.profilePictureStorage = profilePictureStorage;
        this.photoRetentionService = photoRetentionService;
        this.executor = executor;
    }

    /** What the account owns in storage, captured BEFORE its rows are deleted. */
    public record StoredObjects(String profilePictureKey, List<UUID> photoReceiptIds) {
        public boolean isEmpty() {
            return profilePictureKey == null && photoReceiptIds.isEmpty();
        }
    }

    /** Schedules the removal for after the surrounding transaction commits (now, if there is none). */
    public void deleteAfterCommit(StoredObjects storedObjects) {
        if (storedObjects.isEmpty()) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            dispatch(storedObjects);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                dispatch(storedObjects);
            }
        });
    }

    private void dispatch(StoredObjects storedObjects) {
        try {
            executor.execute(() -> deleteStoredObjects(storedObjects));
        } catch (RejectedExecutionException poolFull) {
            log.warn("account_storage.cleanup_rejected profile_picture={} photo_receipts={}",
                    storedObjects.profilePictureKey() != null, storedObjects.photoReceiptIds().size());
        }
    }

    void deleteStoredObjects(StoredObjects storedObjects) {
        var profilePictureRemoved = deleteProfilePicture(storedObjects.profilePictureKey());
        var photoReceiptsRemoved = storedObjects.photoReceiptIds().stream()
                .filter(photoRetentionService::deleteAttempt)
                .count();
        log.info("account_storage.cleanup done profile_picture_removed={} photo_receipts_removed={}/{}",
                profilePictureRemoved, photoReceiptsRemoved, storedObjects.photoReceiptIds().size());
    }

    private boolean deleteProfilePicture(String profilePictureKey) {
        if (profilePictureKey == null) return false;
        try {
            profilePictureStorage.delete(profilePictureKey);
            return true;
        } catch (Exception ex) {
            log.warn("account_storage.profile_picture_delete_failed {}: {}",
                    ex.getClass().getSimpleName(), ex.getMessage());
            return false;
        }
    }
}
