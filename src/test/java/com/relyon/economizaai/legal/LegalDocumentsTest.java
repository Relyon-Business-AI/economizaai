package com.relyon.economizaai.legal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegalDocumentsTest {

    private static final String COMPANY_CNPJ = "69.777.033/0001-49";

    private final LegalDocuments documents = new LegalDocuments();

    @BeforeEach
    void loadDocuments() throws IOException {
        documents.load();
    }

    @Test
    void terms_shouldStateCurrentVersionAndCompany() {
        var terms = documents.getTermsContent();

        assertTrue(terms.contains("**Versão " + LegalDocuments.CURRENT_TERMS_VERSION + "**"));
        assertTrue(terms.contains("ECONOMIZA AI LTDA"));
        assertTrue(terms.contains(COMPANY_CNPJ));
    }

    @Test
    void privacy_shouldStateCurrentVersionAndCompany() {
        var privacy = documents.getPrivacyContent();

        assertTrue(privacy.contains("**Versão " + LegalDocuments.CURRENT_PRIVACY_VERSION + "**"));
        assertTrue(privacy.contains("ECONOMIZA AI LTDA"));
        assertTrue(privacy.contains(COMPANY_CNPJ));
    }

    @Test
    void documents_shouldNotCarryDraftNoticesOrApiPaths() {
        for (var content : new String[] {documents.getTermsContent(), documents.getPrivacyContent()}) {
            assertFalse(content.contains("versão inicial"));
            assertFalse(content.contains("a definir"));
            assertFalse(content.contains("/api/v1/"));
        }
    }
}
