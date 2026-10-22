package com.relyon.economizaai.service.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live opt-in (gasta centavos): prova que o caminho MULTIMODAL do
 * {@link AnthropicChatAdapter} funciona contra a API real — bloco de imagem
 * base64 + prompt num turno só. Roda apenas com ANTHROPIC_API_KEY no ambiente:
 * {@code ANTHROPIC_API_KEY=... ./mvnw test -Dtest=AnthropicVisionLiveTest}.
 */
class AnthropicVisionLiveTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
    void completeWithImage_describesASimpleImage() throws Exception {
        var adapter = new AnthropicChatAdapter();
        ReflectionTestUtils.setField(adapter, "apiKey", System.getenv("ANTHROPIC_API_KEY"));
        ReflectionTestUtils.setField(adapter, "timeoutSeconds", 60);

        // quadrado vermelho 64x64 — resposta esperada é determinística o suficiente
        var image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(java.awt.Color.RED);
        graphics.fillRect(0, 0, 64, 64);
        graphics.dispose();
        var pngBytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", pngBytes);

        var result = adapter.completeWithImage("claude-haiku-4-5-20251001", null,
                "Qual a cor predominante da imagem? Responda APENAS o JSON {\"cor\": \"...\"}",
                pngBytes.toByteArray(), "image/png", 200);

        assertTrue(result.text().toLowerCase().contains("vermelh")
                        || result.text().toLowerCase().contains("red"),
                "resposta: " + result.text());
    }
}
