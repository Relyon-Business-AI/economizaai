package com.relyon.economizaai.controller;

import com.relyon.economizaai.dto.response.LegalDocumentResponse;
import com.relyon.economizaai.legal.LegalDocuments;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/legal")
@RequiredArgsConstructor
@Tag(name = "Legal", description = "Privacy policy and terms of use (publicly accessible)")
public class LegalController {

    private final LegalDocuments documents;

    @GetMapping("/terms")
    public ResponseEntity<LegalDocumentResponse> terms() {
        return ResponseEntity.ok(new LegalDocumentResponse(
                LegalDocuments.CURRENT_TERMS_VERSION, documents.getTermsContent()));
    }

    @GetMapping({"/privacy-policy", "/privacy"})
    public ResponseEntity<LegalDocumentResponse> privacy() {
        return ResponseEntity.ok(new LegalDocumentResponse(
                LegalDocuments.CURRENT_PRIVACY_VERSION, documents.getPrivacyContent()));
    }
}
