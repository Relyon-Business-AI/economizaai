package com.relyon.economizaai.service.profile;

import com.relyon.economizaai.exception.InvalidProfilePictureException;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfilePictureServiceTest {

    @Mock
    private ProfilePictureStorage storage;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private ProfilePictureService profilePictureService;

    private User buildUser() {
        var user = User.builder()
                .id(UUID.randomUUID())
                .name("John Doe")
                .email("john@test.com")
                .password("encoded")
                .build();
        return user;
    }

    private byte[] pngBytes(int width, int height) throws IOException {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private byte[] jpegBytes(int width, int height) throws IOException {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", output);
        return output.toByteArray();
    }

    // ---------- upload: validation ----------

    @Test
    void upload_throwsWhenFileNull() {
        var user = buildUser();

        assertThrows(InvalidProfilePictureException.class, () -> profilePictureService.upload(user, null));
        verifyNoInteractions(storage);
        verifyNoInteractions(userRepository);
    }

    @Test
    void upload_throwsWhenFileEmpty() {
        var user = buildUser();
        MultipartFile emptyFile = new MockMultipartFile("file", "avatar.png", "image/png", new byte[0]);

        assertThrows(InvalidProfilePictureException.class, () -> profilePictureService.upload(user, emptyFile));
        verifyNoInteractions(storage);
    }

    @Test
    void upload_throwsWhenContentTypeNull() {
        var user = buildUser();
        MultipartFile noTypeFile = new MockMultipartFile("file", "avatar", null, new byte[]{1, 2, 3});

        assertThrows(InvalidProfilePictureException.class, () -> profilePictureService.upload(user, noTypeFile));
        verifyNoInteractions(storage);
    }

    @Test
    void upload_throwsWhenContentTypeNotAllowed() {
        var user = buildUser();
        MultipartFile gifFile = new MockMultipartFile("file", "avatar.gif", "image/gif", new byte[]{1, 2, 3});

        assertThrows(InvalidProfilePictureException.class, () -> profilePictureService.upload(user, gifFile));
        verifyNoInteractions(storage);
    }

    @Test
    void upload_throwsWhenTooLarge() {
        var user = buildUser();
        ReflectionTestUtils.setField(profilePictureService, "maxSizeMb", 1);
        var oversized = new byte[2 * 1024 * 1024];
        MultipartFile bigFile = new MockMultipartFile("file", "avatar.png", "image/png", oversized);

        assertThrows(InvalidProfilePictureException.class, () -> profilePictureService.upload(user, bigFile));
        verifyNoInteractions(storage);
    }

    // ---------- upload: happy paths ----------

    @Test
    void upload_storesSmallPngWithoutResizing() throws IOException {
        var user = buildUser();
        ReflectionTestUtils.setField(profilePictureService, "maxSizeMb", 5);
        var smallPng = pngBytes(100, 100);
        MultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", smallPng);
        when(storage.store(any(InputStream.class), eq("image/png"), anyLong())).thenReturn("stored-key-1");

        profilePictureService.upload(user, file);

        assertEquals("stored-key-1", user.getProfilePictureKey());
        assertEquals("image/png", user.getProfilePictureContentType());
        assertNotNull(user.getProfilePictureUploadedAt());
        verify(userRepository).save(user);
        verify(storage, never()).delete(anyString());
    }

    @Test
    void upload_downscalesLargePng() throws IOException {
        var user = buildUser();
        ReflectionTestUtils.setField(profilePictureService, "maxSizeMb", 50);
        var largePng = pngBytes(1024, 768);
        MultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", largePng);
        when(storage.store(any(InputStream.class), eq("image/png"), anyLong())).thenReturn("stored-key-2");

        profilePictureService.upload(user, file);

        assertEquals("stored-key-2", user.getProfilePictureKey());
        verify(userRepository).save(user);
    }

    @Test
    void upload_downscalesLargeJpeg() throws IOException {
        var user = buildUser();
        ReflectionTestUtils.setField(profilePictureService, "maxSizeMb", 50);
        var largeJpeg = jpegBytes(1024, 600);
        MultipartFile file = new MockMultipartFile("file", "avatar.jpg", "image/jpeg", largeJpeg);
        when(storage.store(any(InputStream.class), eq("image/jpeg"), anyLong())).thenReturn("stored-key-jpeg");

        profilePictureService.upload(user, file);

        assertEquals("stored-key-jpeg", user.getProfilePictureKey());
    }

    @Test
    void upload_passesThroughWebpWithoutDecoding() throws IOException {
        var user = buildUser();
        ReflectionTestUtils.setField(profilePictureService, "maxSizeMb", 5);
        MultipartFile file = new MockMultipartFile("file", "avatar.webp", "image/webp", new byte[]{9, 8, 7, 6});
        when(storage.store(any(InputStream.class), eq("image/webp"), anyLong())).thenReturn("stored-webp");

        profilePictureService.upload(user, file);

        assertEquals("stored-webp", user.getProfilePictureKey());
        assertEquals("image/webp", user.getProfilePictureContentType());
    }

    @Test
    void upload_passesThroughUndecodableBytesUnchanged() throws IOException {
        var user = buildUser();
        ReflectionTestUtils.setField(profilePictureService, "maxSizeMb", 5);
        // Declared as png but not actually decodable -> ImageIO.read returns null.
        MultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", new byte[]{1, 2, 3, 4, 5});
        when(storage.store(any(InputStream.class), eq("image/png"), anyLong())).thenReturn("stored-raw");

        profilePictureService.upload(user, file);

        assertEquals("stored-raw", user.getProfilePictureKey());
    }

    @Test
    void upload_deletesPreviousKeyAfterSuccessfulStore() throws IOException {
        var user = buildUser();
        user.setProfilePictureKey("old-key");
        ReflectionTestUtils.setField(profilePictureService, "maxSizeMb", 5);
        var smallPng = pngBytes(50, 50);
        MultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", smallPng);
        when(storage.store(any(InputStream.class), eq("image/png"), anyLong())).thenReturn("new-key");

        profilePictureService.upload(user, file);

        assertEquals("new-key", user.getProfilePictureKey());
        verify(storage).delete("old-key");
    }

    @Test
    void upload_swallowsPreviousKeyDeleteFailure() throws IOException {
        var user = buildUser();
        user.setProfilePictureKey("old-key");
        ReflectionTestUtils.setField(profilePictureService, "maxSizeMb", 5);
        var smallPng = pngBytes(50, 50);
        MultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", smallPng);
        when(storage.store(any(InputStream.class), eq("image/png"), anyLong())).thenReturn("new-key");
        org.mockito.Mockito.doThrow(new IOException("boom")).when(storage).delete("old-key");

        profilePictureService.upload(user, file);

        assertEquals("new-key", user.getProfilePictureKey());
        verify(userRepository).save(user);
    }

    @Test
    void upload_wrapsStorageIoExceptionAsInvalidPicture() throws IOException {
        var user = buildUser();
        ReflectionTestUtils.setField(profilePictureService, "maxSizeMb", 5);
        var smallPng = pngBytes(50, 50);
        MultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", smallPng);
        when(storage.store(any(InputStream.class), anyString(), anyLong())).thenThrow(new IOException("disk full"));

        assertThrows(InvalidProfilePictureException.class, () -> profilePictureService.upload(user, file));
        verify(userRepository, never()).save(any());
    }

    @Test
    void upload_acceptsMixedCaseContentType() throws IOException {
        var user = buildUser();
        ReflectionTestUtils.setField(profilePictureService, "maxSizeMb", 5);
        var smallPng = pngBytes(50, 50);
        MultipartFile file = new MockMultipartFile("file", "avatar.PNG", "IMAGE/PNG", smallPng);
        when(storage.store(any(InputStream.class), anyString(), anyLong())).thenReturn("ci-key");

        profilePictureService.upload(user, file);

        assertEquals("ci-key", user.getProfilePictureKey());
    }

    // ---------- read ----------

    @Test
    void read_returnsStoredBytesWhenKeyPresent() throws IOException {
        var user = buildUser();
        user.setProfilePictureKey("key-1");
        user.setProfilePictureContentType("image/png");
        var storedBytes = new byte[]{10, 20, 30};
        when(storage.read("key-1")).thenReturn(storedBytes);

        var result = profilePictureService.read(user);

        assertEquals(storedBytes, result.bytes());
        assertEquals("image/png", result.contentType());
        assertFalse(result.fallback());
    }

    @Test
    void read_fallsBackToInitialsWhenStoredBytesNull() throws IOException {
        var user = buildUser();
        user.setProfilePictureKey("missing-key");
        when(storage.read("missing-key")).thenReturn(null);

        var result = profilePictureService.read(user);

        assertTrue(result.fallback());
        assertEquals("image/png", result.contentType());
        assertNotNull(result.bytes());
        assertTrue(result.bytes().length > 0);
    }

    @Test
    void read_fallsBackToInitialsWhenStorageThrows() throws IOException {
        var user = buildUser();
        user.setProfilePictureKey("broken-key");
        when(storage.read("broken-key")).thenThrow(new IOException("read error"));

        var result = profilePictureService.read(user);

        assertTrue(result.fallback());
        assertNotNull(result.bytes());
    }

    @Test
    void read_generatesInitialsAvatarWhenNoKey() {
        var user = buildUser();

        var result = profilePictureService.read(user);

        assertTrue(result.fallback());
        assertNotNull(result.bytes());
        assertTrue(result.bytes().length > 0);
        verifyNoInteractions(storage);
    }

    @Test
    void read_initialsAvatarForSingleWordName() {
        var user = User.builder().id(UUID.randomUUID()).name("Madonna").email("m@test.com").build();

        var result = profilePictureService.read(user);

        assertTrue(result.fallback());
        assertNotNull(result.bytes());
    }

    @Test
    void read_initialsAvatarFallsBackToEmailWhenNameBlank() {
        var user = User.builder().id(UUID.randomUUID()).name("   ").email("zoe@test.com").build();

        var result = profilePictureService.read(user);

        assertTrue(result.fallback());
        assertNotNull(result.bytes());
    }

    @Test
    void read_initialsAvatarFallsBackToQuestionMarkWhenNameAndEmailAbsent() {
        var user = User.builder().id(UUID.randomUUID()).name(null).email(null).build();

        var result = profilePictureService.read(user);

        assertTrue(result.fallback());
        assertNotNull(result.bytes());
    }

    @Test
    void read_initialsAvatarUsesNameSeedWhenEmailNull() {
        var user = User.builder().id(UUID.randomUUID()).name("Alpha Beta").email(null).build();

        var result = profilePictureService.read(user);

        assertTrue(result.fallback());
        assertNotNull(result.bytes());
    }

    // ---------- delete ----------

    @Test
    void delete_clearsFieldsAndRemovesStoredKey() throws IOException {
        var user = buildUser();
        user.setProfilePictureKey("key-to-remove");
        user.setProfilePictureContentType("image/png");

        profilePictureService.delete(user);

        assertNull(user.getProfilePictureKey());
        assertNull(user.getProfilePictureContentType());
        assertNull(user.getProfilePictureUploadedAt());
        verify(userRepository).save(user);
        verify(storage).delete("key-to-remove");
    }

    @Test
    void delete_isNoOpWhenNoKey() {
        var user = buildUser();
        assertNull(user.getProfilePictureKey());

        profilePictureService.delete(user);

        verify(userRepository, never()).save(any());
        verifyNoInteractions(storage);
    }

    @Test
    void delete_swallowsStorageDeleteFailure() throws IOException {
        var user = buildUser();
        user.setProfilePictureKey("key-x");
        org.mockito.Mockito.doThrow(new IOException("nope")).when(storage).delete("key-x");

        profilePictureService.delete(user);

        assertNull(user.getProfilePictureKey());
        verify(userRepository).save(user);
    }

    @Test
    void upload_passesProcessedContentTypeToStorage() throws IOException {
        var user = buildUser();
        ReflectionTestUtils.setField(profilePictureService, "maxSizeMb", 5);
        var smallPng = pngBytes(40, 40);
        MultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", smallPng);
        when(storage.store(any(InputStream.class), anyString(), anyLong())).thenReturn("k");
        var contentTypeCaptor = ArgumentCaptor.forClass(String.class);

        profilePictureService.upload(user, file);

        verify(storage).store(any(InputStream.class), contentTypeCaptor.capture(), anyLong());
        assertEquals("image/png", contentTypeCaptor.getValue());
    }
}
