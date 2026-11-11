package com.relyon.economizaai.service.photoattempt;

import com.relyon.economizaai.service.photoattempt.PhotoAttemptStorage.StoredPhoto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TEMPORARY / testing: keeps the raw photos sent to each photo-receipt extraction
 * attempt (in R2) so we can replay the exact inputs while tuning the vision
 * prompt/model. Best-effort — a storage failure never breaks ingestion.
 *
 * <p>Two off-switches so it's easy to retire: the boot default comes from
 * {@code economizaai.photo-retention.enabled} (env, needs a restart), and an
 * admin endpoint flips it live in-memory (no redeploy) until the next restart.
 * The storage bean exists only where R2 is configured; without it this no-ops.
 */
@Slf4j
@Service
public class PhotoRetentionService {

    private final ObjectProvider<PhotoAttemptStorage> storageProvider;
    private final AtomicBoolean enabled;

    public PhotoRetentionService(ObjectProvider<PhotoAttemptStorage> storageProvider,
                                 @Value("${economizaai.photo-retention.enabled:false}") boolean enabledDefault) {
        this.storageProvider = storageProvider;
        this.enabled = new AtomicBoolean(enabledDefault);
    }

    /** True only when the toggle is on AND an R2 storage backend is wired. */
    public boolean isActive() {
        return enabled.get() && storageProvider.getIfAvailable() != null;
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    /** Admin live toggle (in-memory; resets to the env default on restart). */
    public void setEnabled(boolean value, String by) {
        enabled.set(value);
        log.info("photo_retention.toggle enabled={} by={}", value, by);
    }

    /** Best-effort: never throws — retention must not affect the user's nota. */
    public void saveAttempt(UUID receiptId, List<byte[]> photos, List<String> mediaTypes) {
        if (!isActive()) return;
        var storage = storageProvider.getIfAvailable();
        if (storage == null) return;
        try {
            storage.store(receiptId.toString(), photos, mediaTypes);
        } catch (Exception ex) {
            log.warn("photo_retention.save_failed receipt={} reason={}", receiptId, ex.getMessage());
        }
    }

    /**
     * Removes the photos kept for a receipt. Runs whenever a storage backend is wired, even
     * with the retention toggle off (photos saved earlier must still go). Best-effort: never
     * throws; returns whether the removal went through.
     */
    public boolean deleteAttempt(UUID receiptId) {
        var storage = storageProvider.getIfAvailable();
        if (storage == null) return true;
        try {
            storage.delete(receiptId.toString());
            return true;
        } catch (Exception ex) {
            log.warn("photo_retention.delete_failed receipt={} reason={}", receiptId, ex.getMessage());
            return false;
        }
    }

    /** Photos saved for a receipt (admin retrieval); empty if none/disabled. */
    public List<StoredPhoto> list(UUID receiptId) {
        var storage = storageProvider.getIfAvailable();
        if (storage == null) return List.of();
        try {
            return storage.list(receiptId.toString());
        } catch (Exception ex) {
            log.warn("photo_retention.list_failed receipt={} reason={}", receiptId, ex.getMessage());
            return List.of();
        }
    }

    /** Bytes of one stored photo by key (admin download); null if absent/unavailable. */
    public byte[] read(String key) {
        var storage = storageProvider.getIfAvailable();
        if (storage == null) return null;
        try {
            return storage.read(key);
        } catch (Exception ex) {
            log.warn("photo_retention.read_failed key={} reason={}", key, ex.getMessage());
            return null;
        }
    }
}
