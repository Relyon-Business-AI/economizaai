package com.relyon.economizaai.service.photoattempt;

import java.io.IOException;
import java.util.List;

/**
 * Stores the raw photos sent to each photo-receipt extraction attempt so we can
 * replay the exact inputs while tuning the vision prompt/model. Temporary / for
 * testing — gated by {@code economizaai.photo-retention.enabled}; only wired when
 * R2 is configured (prod). See {@link CloudflareR2PhotoAttemptStorage}.
 */
public interface PhotoAttemptStorage {

    /** One stored photo: its R2 key, the 0-based index in the attempt, and the media type. */
    record StoredPhoto(String key, int index, String mediaType) {}

    /** Overwrites all photos for this receipt and writes a manifest. Returns what was stored. */
    List<StoredPhoto> store(String receiptId, List<byte[]> photos, List<String> mediaTypes) throws IOException;

    /** The photos saved for this receipt (from the manifest), empty if none. */
    List<StoredPhoto> list(String receiptId) throws IOException;

    /** Bytes of one stored photo, or null if the key is absent. */
    byte[] read(String key) throws IOException;
}
