package com.relyon.economizaai.controller;

import com.relyon.economizaai.model.User;
import com.relyon.economizaai.service.profile.ProfilePictureService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Duration;

/**
 * One way to serve a profile picture (own or a household member's): strong ETag,
 * {@code If-None-Match} → 304 without touching storage, short private cache so a screen
 * re-focus doesn't even hit the network, and the fallback marker header on both.
 */
final class ProfilePictureResponses {

    static final String FALLBACK_HEADER = "X-Profile-Picture-Fallback";
    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate();

    private ProfilePictureResponses() {}

    static ResponseEntity<byte[]> respond(ProfilePictureService service, User subject, String ifNoneMatch) {
        var etag = service.etag(subject);
        if (ifNoneMatch != null && matches(ifNoneMatch, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .cacheControl(CACHE)
                    .header(FALLBACK_HEADER, String.valueOf(subject.getProfilePictureKey() == null))
                    .build();
        }
        var pic = service.read(subject);
        return ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(CACHE)
                .header(FALLBACK_HEADER, String.valueOf(pic.fallback()))
                .contentType(MediaType.parseMediaType(pic.contentType() == null ? "application/octet-stream" : pic.contentType()))
                .body(pic.bytes());
    }

    // Clients may send several tags, weak tags or "*"; compare value by value, ignoring W/.
    private static boolean matches(String ifNoneMatch, String etag) {
        for (var candidate : ifNoneMatch.split(",")) {
            var trimmed = candidate.trim();
            if ("*".equals(trimmed)) return true;
            if (trimmed.startsWith("W/")) trimmed = trimmed.substring(2);
            if (trimmed.equals(etag)) return true;
        }
        return false;
    }
}
