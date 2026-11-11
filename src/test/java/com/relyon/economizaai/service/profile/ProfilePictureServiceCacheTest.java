package com.relyon.economizaai.service.profile;

import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Spy;
import com.relyon.economizaai.service.scan.ImageDecodeGate;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ETag + hot cache: the second read of the same picture never goes back to storage. */
@ExtendWith(MockitoExtension.class)
class ProfilePictureServiceCacheTest {

    @Mock
    private ProfilePictureStorage storage;

    @Mock
    private UserRepository userRepository;
    @Spy
    private ImageDecodeGate imageDecodeGate = new ImageDecodeGate(3, 5000);


    @InjectMocks
    private ProfilePictureService profilePictureService;

    private User user(String key) {
        return User.builder().id(UUID.randomUUID()).name("Ana Maria").email("ana@test.com")
                .profilePictureKey(key).profilePictureContentType(key == null ? null : "image/jpeg").build();
    }

    @Test
    void etag_isTheStorageKeyForUploads_andStableHashForInitials() {
        assertEquals("\"pp/abc.jpg\"", profilePictureService.etag(user("pp/abc.jpg")));
        var initials = profilePictureService.etag(user(null));
        assertTrue(initials.startsWith("\"initials-"));
        assertEquals(initials, profilePictureService.etag(user(null)), "same name+email → same tag");
        assertNotEquals(initials, profilePictureService.etag(
                User.builder().id(UUID.randomUUID()).name("Bruno").email("bruno@test.com").build()));
    }

    @Test
    void read_hitsStorageOnceThenServesFromCache() throws IOException {
        var ana = user("pp/abc.jpg");
        when(storage.read("pp/abc.jpg")).thenReturn(new byte[] {7, 8, 9});

        var first = profilePictureService.read(ana);
        var second = profilePictureService.read(ana);

        assertArrayEquals(new byte[] {7, 8, 9}, first.bytes());
        assertArrayEquals(first.bytes(), second.bytes());
        verify(storage, times(1)).read("pp/abc.jpg");
    }

    @Test
    void read_generatedInitialsAreCachedByEtagToo() {
        var ana = user(null);

        var first = profilePictureService.read(ana);
        var second = profilePictureService.read(ana);

        assertTrue(first.fallback());
        assertArrayEquals(first.bytes(), second.bytes());
    }

    @Test
    void delete_dropsTheCachedBytes() throws IOException {
        var ana = user("pp/abc.jpg");
        when(storage.read("pp/abc.jpg")).thenReturn(new byte[] {1});
        profilePictureService.read(ana);

        profilePictureService.delete(ana);

        // key is gone now → initials, generated (not the stale upload bytes)
        var after = profilePictureService.read(ana);
        assertTrue(after.fallback());
    }
}
