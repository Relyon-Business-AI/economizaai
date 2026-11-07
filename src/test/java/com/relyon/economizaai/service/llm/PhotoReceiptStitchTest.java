package com.relyon.economizaai.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Costura de fotos sobrepostas: a junção de duas fotos consecutivas deve descartar o
 * run contíguo repetido (sobreposição), sem tocar em repetições legítimas espalhadas.
 */
class PhotoReceiptStitchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PhotoReceiptExtractionService service =
            new PhotoReceiptExtractionService(null, null, null, null, null, null, null, true, 60, 6);

    /** Monta uma "extração" (uma foto) com itens "desc|qtd|unit|total". */
    private JsonNode photo(String... items) {
        var sb = new StringBuilder("{\"items\":[");
        for (var i = 0; i < items.length; i++) {
            var parts = items[i].split("\\|");
            if (i > 0) sb.append(",");
            sb.append(String.format("{\"description\":\"%s\",\"quantity\":%s,\"unit\":\"%s\",\"total_price\":%s}",
                    parts[0], parts[1], parts[2], parts[3]));
        }
        sb.append("]}");
        try {
            return MAPPER.readTree(sb.toString());
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private List<String> descriptions(List<PhotoReceiptExtractionService.PhotoItem> items) {
        return items.stream().map(PhotoReceiptExtractionService.PhotoItem::description).toList();
    }

    @Test
    void mergesOverlapAtJunction() {
        // Foto 1 termina com C,D; foto 2 começa com C,D (mesma sobreposição) e segue E,F.
        var photo1 = photo("A|1|UN|1.00", "B|1|UN|2.00", "C|1|UN|3.00", "D|1|UN|4.00");
        var photo2 = photo("C|1|UN|3.00", "D|1|UN|4.00", "E|1|UN|5.00", "F|1|UN|6.00");

        var merged = service.stitchItems(List.of(photo1, photo2));

        assertEquals(List.of("A", "B", "C", "D", "E", "F"), descriptions(merged));
    }

    @Test
    void keepsLegitRepeatNotAtJunction() {
        // "LEITE" aparece 2x na MESMA foto (compra legítima fora de ordem) — não é sobreposição.
        var photo1 = photo("LEITE|1|UN|5.00", "PAO|1|UN|2.00", "LEITE|1|UN|5.00", "CAFE|1|UN|9.00");
        var photo2 = photo("CAFE|1|UN|9.00", "ACUCAR|1|UN|4.00");

        var merged = service.stitchItems(List.of(photo1, photo2));

        // Mantém os dois LEITE; só funde o CAFE da junção.
        assertEquals(List.of("LEITE", "PAO", "LEITE", "CAFE", "ACUCAR"), descriptions(merged));
    }

    @Test
    void concatenatesWhenNoOverlap() {
        var photo1 = photo("A|1|UN|1.00", "B|1|UN|2.00");
        var photo2 = photo("C|1|UN|3.00", "D|1|UN|4.00");

        var merged = service.stitchItems(List.of(photo1, photo2));

        assertEquals(List.of("A", "B", "C", "D"), descriptions(merged));
    }

    @Test
    void differentPriceIsNotOverlap() {
        // Mesma descrição mas preço diferente na junção → NÃO é o mesmo item, mantém os dois.
        var photo1 = photo("A|1|UN|1.00", "LEITE|1|UN|5.00");
        var photo2 = photo("LEITE|1|UN|7.00", "B|1|UN|2.00");

        var merged = service.stitchItems(List.of(photo1, photo2));

        assertEquals(List.of("A", "LEITE", "LEITE", "B"), descriptions(merged));
    }
}
