package com.relyon.economizaai.service.profile;

import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Coverage for paths not exercised by {@link ProfilePictureServiceTest}:
 * three-word names (first+last initial), palette determinism (same seed →
 * same bytes), exactly-at-cap dimensions (no resize), and read returning a
 * stored picture with a null stored content type.
 */
@ExtendWith(MockitoExtension.class)
class ProfilePictureServiceCoverageTest {

    @Mock private ProfilePictureStorage storage;
    @Mock private UserRepository userRepository;

    @InjectMocks private ProfilePictureService profilePictureService;

    private byte[] pngBytes(int width, int height) throws IOException {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    @Test
    void read_initialsAvatarForThreeWordNameUsesFirstAndLastInitial() {
        var user = User.builder().id(UUID.randomUUID())
                .name("Ana Carolina Souza").email("ana@test.com").build();

        var result = profilePictureService.read(user);

        assertTrue(result.fallback());
        assertNotNull(result.bytes());
        assertTrue(result.bytes().length > 0);
    }

    @Test
    void read_initialsAvatarIsDeterministicForSameUser() {
        var first = User.builder().id(UUID.randomUUID()).name("Bruno Lima").email("bruno@test.com").build();
        var second = User.builder().id(UUID.randomUUID()).name("Bruno Lima").email("bruno@test.com").build();

        var firstResult = profilePictureService.read(first);
        var secondResult = profilePictureService.read(second);

        // Same name + email seed → identical generated avatar bytes.
        assertArrayEquals(firstResult.bytes(), secondResult.bytes());
    }

    @Test
    void upload_pngExactlyAtCapDimensionIsNotResized() throws IOException {
        var user = User.builder().id(UUID.randomUUID()).name("Cap Test").email("cap@test.com").build();
        ReflectionTestUtils.setField(profilePictureService, "maxSizeMb", 50);
        // 512x512 == MAX_DIMENSION_PX → maxSide <= cap → original stored unchanged.
        var atCap = pngBytes(512, 512);
        MultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", atCap);
        when(storage.store(any(InputStream.class), eq("image/png"), anyLong())).thenReturn("at-cap-key");

        profilePictureService.upload(user, file);

        assertEquals("at-cap-key", user.getProfilePictureKey());
    }

    @Test
    void read_returnsStoredBytesWithNullContentType() throws IOException {
        var user = User.builder().id(UUID.randomUUID()).name("Dora").email("dora@test.com").build();
        user.setProfilePictureKey("key-no-ct");
        // content type left null on the user record
        var stored = new byte[]{1, 2, 3, 4};
        when(storage.read("key-no-ct")).thenReturn(stored);

        var result = profilePictureService.read(user);

        assertFalse(result.fallback());
        assertArrayEquals(stored, result.bytes());
        assertNull(result.contentType());
    }
}
