package com.relyon.economizaai.service.profile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalDiskProfilePictureStorageTest {

    @TempDir
    Path tempDir;

    private LocalDiskProfilePictureStorage storage;

    @BeforeEach
    void setUp() {
        // construct with an arbitrary value, then point baseDir at the temp dir
        storage = new LocalDiskProfilePictureStorage(tempDir.toString());
        ReflectionTestUtils.setField(storage, "baseDir", tempDir);
    }

    @Test
    void store_thenRead_roundTrips() throws IOException {
        var payload = "hello-bytes".getBytes(StandardCharsets.UTF_8);

        var key = storage.store(new ByteArrayInputStream(payload), "image/png", payload.length);

        assertTrue(key.endsWith(".png"));
        assertArrayEquals(payload, storage.read(key));
        assertTrue(Files.exists(tempDir.resolve(key)));
    }

    @Test
    void store_jpegContentType_usesJpgExtension() throws IOException {
        var payload = new byte[]{1, 2, 3};
        var key = storage.store(new ByteArrayInputStream(payload), "image/jpeg", payload.length);
        assertTrue(key.endsWith(".jpg"));
    }

    @Test
    void store_jpgContentType_usesJpgExtension() throws IOException {
        var payload = new byte[]{4, 5};
        var key = storage.store(new ByteArrayInputStream(payload), "IMAGE/JPG", payload.length);
        assertTrue(key.endsWith(".jpg"));
    }

    @Test
    void store_webpContentType_usesWebpExtension() throws IOException {
        var payload = new byte[]{6};
        var key = storage.store(new ByteArrayInputStream(payload), "image/webp", payload.length);
        assertTrue(key.endsWith(".webp"));
    }

    @Test
    void store_unknownContentType_hasNoExtension() throws IOException {
        var payload = new byte[]{7};
        var key = storage.store(new ByteArrayInputStream(payload), "application/octet-stream", payload.length);
        assertFalse(key.contains("."));
    }

    @Test
    void store_nullContentType_hasNoExtension() throws IOException {
        var payload = new byte[]{8};
        var key = storage.store(new ByteArrayInputStream(payload), null, payload.length);
        assertFalse(key.contains("."));
    }

    @Test
    void read_missingKey_returnsNull() throws IOException {
        assertNull(storage.read("does-not-exist.png"));
    }

    @Test
    void delete_existingKey_removesFile() throws IOException {
        var payload = new byte[]{9, 9};
        var key = storage.store(new ByteArrayInputStream(payload), "image/png", payload.length);
        assertTrue(Files.exists(tempDir.resolve(key)));

        storage.delete(key);

        assertFalse(Files.exists(tempDir.resolve(key)));
    }

    @Test
    void delete_missingKey_isSilent() {
        assertDoesNotThrow(() -> storage.delete("nope.png"));
    }

    @Test
    void delete_nullKey_isSilent() {
        assertDoesNotThrow(() -> storage.delete(null));
    }

    @Test
    void read_traversalKey_throwsAndDoesNotEscapeBaseDir() {
        assertThrows(IOException.class, () -> storage.read("../../etc/passwd"));
        assertThrows(IOException.class, () -> storage.read("../outside.png"));
    }

    @Test
    void delete_traversalKey_throwsAndDoesNotEscapeBaseDir() {
        assertThrows(IOException.class, () -> storage.delete("../../tmp/anything"));
    }

    @Test
    void read_keyWithHarmlessDotSegments_staysWithinBaseDir() throws IOException {
        var payload = new byte[]{1, 2, 3};
        var key = storage.store(new ByteArrayInputStream(payload), "image/png", payload.length);
        // "sub/../<key>" normalizes back to <key>, which is still inside baseDir
        assertArrayEquals(payload, storage.read("sub/../" + key));
    }

    @Test
    void ensureDir_createsBaseDirectory() throws IOException {
        var nested = tempDir.resolve("nested").resolve("pics");
        var freshStorage = new LocalDiskProfilePictureStorage(nested.toString());

        ReflectionTestUtils.invokeMethod(freshStorage, "ensureDir");

        assertTrue(Files.isDirectory(nested));
    }
}
