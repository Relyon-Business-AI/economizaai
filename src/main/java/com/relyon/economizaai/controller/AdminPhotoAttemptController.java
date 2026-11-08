package com.relyon.economizaai.controller;

import com.relyon.economizaai.model.User;
import com.relyon.economizaai.service.photoattempt.PhotoRetentionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * TEMPORARY / testing: lets an admin retrieve the exact photos that were sent to a
 * photo-receipt extraction attempt, so we can replay them while tuning the vision
 * prompt/model, and flip the retention toggle live. ADMIN-only via SecurityConfig
 * ({@code /api/v1/admin/**}). Retire together with {@link PhotoRetentionService}.
 */
@Tag(name = "Admin - Photo Attempts")
@RestController
@RequestMapping("/api/v1/admin/photo-attempts")
@RequiredArgsConstructor
public class AdminPhotoAttemptController {

    private final PhotoRetentionService photoRetentionService;

    public record PhotoAttemptSettings(boolean enabled) {}

    public record PhotoAttemptItem(int index, String mediaType, String downloadPath) {}

    @Operation(summary = "Estado do toggle de retenção de fotos (temporário).")
    @GetMapping("/settings")
    public PhotoAttemptSettings settings() {
        return new PhotoAttemptSettings(photoRetentionService.isEnabled());
    }

    @Operation(summary = "Liga/desliga a retenção de fotos ao vivo (em memória, até reiniciar).")
    @PostMapping("/settings")
    public PhotoAttemptSettings updateSettings(@AuthenticationPrincipal User admin,
                                               @RequestBody PhotoAttemptSettings request) {
        photoRetentionService.setEnabled(request.enabled(), admin.getEmail());
        return new PhotoAttemptSettings(photoRetentionService.isEnabled());
    }

    @Operation(summary = "Lista as fotos guardadas de uma tentativa de extração (por receiptId).")
    @GetMapping("/{receiptId}")
    public Map<String, Object> list(@PathVariable UUID receiptId) {
        var photos = photoRetentionService.list(receiptId).stream()
                .map(stored -> new PhotoAttemptItem(stored.index(), stored.mediaType(),
                        "/api/v1/admin/photo-attempts/" + receiptId + "/" + stored.index()))
                .toList();
        return Map.of("receiptId", receiptId.toString(), "count", photos.size(), "photos", photos);
    }

    @Operation(summary = "Baixa uma foto guardada da tentativa (bytes).")
    @GetMapping("/{receiptId}/{index}")
    public ResponseEntity<byte[]> download(@PathVariable UUID receiptId, @PathVariable int index) {
        var photos = photoRetentionService.list(receiptId);
        var match = photos.stream().filter(stored -> stored.index() == index).findFirst().orElse(null);
        if (match == null) {
            return ResponseEntity.notFound().build();
        }
        var bytes = photoRetentionService.read(match.key());
        if (bytes == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(match.mediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + receiptId + "-" + index + "\"")
                .body(bytes);
    }
}
