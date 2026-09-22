package com.relyon.economizaai.legal;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
@Getter
public class LegalDocuments {

    public static final String CURRENT_TERMS_VERSION = "1.0";
    public static final String CURRENT_PRIVACY_VERSION = "1.0";

    private String termsContent;
    private String privacyContent;

    @PostConstruct
    void load() throws IOException {
        termsContent = read("legal/terms-of-use-pt-br.md");
        privacyContent = read("legal/privacy-policy-pt-br.md");
    }

    private static String read(String path) throws IOException {
        try (var stream = new ClassPathResource(path).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
