package com.relyon.economizaai.service.profile;

import java.io.IOException;
import java.io.InputStream;

/**
 * Pluggable backend for profile-picture bytes. The User row stores an opaque
 * {@code key} returned by {@link #store(InputStream, String, long)}; reading
 * back goes through {@link #read(String)}. Swap the impl (local disk, S3,
 * Cloudinary, ...) without touching callers — that's the whole point.
 *
 * <p>See DEV_NOTES.md for the prod-storage migration plan.
 */
public interface ProfilePictureStorage {

    /**
     * Persist the bytes and return an opaque key the caller saves alongside
     * the user record. Implementations may rename, compress, or rehost — the
     * key is whatever lets {@link #read(String)} find it again.
     */
    String store(InputStream bytes, String contentType, long sizeBytes) throws IOException;

    /** Return the raw bytes for a stored key, or null if not found. */
    byte[] read(String key) throws IOException;

    /** Best-effort delete. Returns silently if the key doesn't exist. */
    void delete(String key) throws IOException;
}
