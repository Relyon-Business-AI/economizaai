package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.request.ContactRequest;
import com.relyon.economizaai.service.ContactService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/contact")
@RequiredArgsConstructor
@Tag(name = "Contact", description = "Public contact / feedback form — emails the message to support")
public class ContactController {

    private final ContactService contactService;

    /** Public (no auth), IP rate-limited. Emails the message to our support inbox. */
    @PostMapping
    public ResponseEntity<Void> submit(@Valid @RequestBody ContactRequest request) {
        contactService.submit(request);
        return ResponseEntity.accepted().build();
    }
}
