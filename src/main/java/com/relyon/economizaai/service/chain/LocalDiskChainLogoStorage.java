package com.relyon.economizaai.service.chain;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Slf4j
@Component
public class LocalDiskChainLogoStorage implements ChainLogoStorage {

    private final Path baseDir;

    public LocalDiskChainLogoStorage(
            @Value("${economizaai.chain-logo.local-dir:/tmp/economizai/chain-logos}") String dir) {
        this.baseDir = Paths.get(dir).toAbsolutePath().normalize();
    }

    @PostConstruct
    void ensureDir() throws IOException {
        Files.createDirectories(baseDir);
        log.info("chain_logo.storage.local_disk dir={}", baseDir);
    }

    @Override
    public String store(InputStream bytes, String contentType, long sizeBytes) throws IOException {
        var key = UUID.randomUUID().toString() + extensionFor(contentType);
        var target = baseDir.resolve(key);
        Files.copy(bytes, target);
        log.info("chain_logo.stored key={} size={}", key, sizeBytes);
        return key;
    }

    @Override
    public byte[] read(String key) throws IOException {
        var path = resolveWithinBase(key);
        if (!Files.exists(path)) return null;
        return Files.readAllBytes(path);
    }

    @Override
    public void delete(String key) throws IOException {
        if (key == null) return;
        Files.deleteIfExists(resolveWithinBase(key));
    }

    private Path resolveWithinBase(String key) throws IOException {
        var resolved = baseDir.resolve(key).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new IOException("Chain logo key escapes storage directory");
        }
        return resolved;
    }

    private String extensionFor(String contentType) {
        if (contentType == null) return "";
        return switch (contentType.toLowerCase()) {
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/svg+xml" -> ".svg";
            default -> "";
        };
    }
}
