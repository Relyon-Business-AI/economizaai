package com.relyon.economizaai.service.profile;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * One-time migration of profile pictures from the local Render disk
 * ({@code /data/profile-pics}) to R2, so the disk can be detached and deploys
 * become zero-downtime (Render can't overlap old/new instances while a disk is
 * attached). Runs on the single deploy where BOTH the disk is still mounted and
 * {@code storage=r2}; gated by {@code economizaai.profile-picture.migrate-disk-to-r2=true}.
 *
 * <p>Idempotent-ish: it overwrites each key (same filename → same R2 object),
 * so a re-run is harmless. Remove the flag + detach the disk once the logs show
 * {@code migrate done}. Never throws — a hiccup must not fail app startup.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "economizaai.profile-picture.migrate-disk-to-r2", havingValue = "true")
@RequiredArgsConstructor
public class DiskToR2ProfilePictureMigration implements ApplicationRunner {

    private final CloudflareR2ProfilePictureStorage r2;

    @Value("${economizaai.profile-picture.local-dir:/tmp/economizai/profile-pics}")
    private String localDir;

    @Override
    public void run(ApplicationArguments args) {
        var dir = Paths.get(localDir);
        if (!Files.isDirectory(dir)) {
            log.info("profile_picture.migrate skip reason=dir_not_found dir={}", dir);
            return;
        }
        var migrated = 0;
        var failed = 0;
        try (var files = Files.list(dir)) {
            for (var path : (Iterable<Path>) files::iterator) {
                if (!Files.isRegularFile(path)) continue;
                var key = path.getFileName().toString();
                try {
                    r2.putObject(key, Files.readAllBytes(path), contentTypeFor(key));
                    migrated++;
                } catch (Exception ex) {
                    failed++;
                    log.warn("profile_picture.migrate.failed key={} {}: {}",
                            key, ex.getClass().getSimpleName(), ex.getMessage());
                }
            }
        } catch (IOException ex) {
            log.error("profile_picture.migrate list_failed dir={} {}", dir, ex.getMessage());
        }
        log.info("profile_picture.migrate done migrated={} failed={}", migrated, failed);
    }

    private String contentTypeFor(String key) {
        var lower = key.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }
}
