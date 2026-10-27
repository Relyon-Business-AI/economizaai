package com.relyon.economizaai.controller;

import com.relyon.economizaai.service.admin.AdminMarketChainService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/chains")
@RequiredArgsConstructor
public class MarketChainController {

    private final AdminMarketChainService adminMarketChainService;

    @GetMapping("/logo/{id}")
    public ResponseEntity<byte[]> serveLogo(@PathVariable UUID id) throws IOException {
        var bytes = adminMarketChainService.readLogo(id);
        if (bytes == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .contentType(MediaType.IMAGE_PNG)
                .body(bytes);
    }
}
