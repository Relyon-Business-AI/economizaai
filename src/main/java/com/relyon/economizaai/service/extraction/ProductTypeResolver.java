package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.EanCatalogEntry;
import com.relyon.economizaai.model.enums.AiActivity;
import com.relyon.economizaai.repository.EanCatalogRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.ai.AiGateway;
import com.relyon.economizaai.service.canonicalization.DescriptionNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

/**
 * Resolve o TIPO canônico de um produto ({@code generic_name_norm}, ex.: "creme de leite") a
 * partir de uma descrição crua — o nome do catálogo (Open Food Facts) ou a prévia de um scan.
 *
 * <p>Reutiliza o MESMO {@link ProductExtractor} da ingestão de nota (que transforma a descrição
 * de um item em {@code genericName}), em vez de uma heurística de prefixo: nomes de catálogo são
 * ruidosos ("Creme De Leite Esterilizado Bate Chantilly Nestlé Lata 280g", e até falso-positivos
 * como "Macarrão ... Creme De Leite Nissin"), então só o dicionário/classificador resolve o tipo
 * corretamente. Fallback para o nome cru normalizado quando o extrator não resolve nada.
 *
 * <p><b>Nomes estrangeiros/órfãos (OFF).</b> O Open Food Facts às vezes guarda o nome em inglês
 * ou lixo para produtos brasileiros (EAN 7896079431110 = arroz branco, OFF {@code generic_name} =
 * "White Rice"). O extrator normaliza para "white rice", que NÃO casa com o catálogo pt ("arroz")
 * → comparação vazia. {@link #resolveCatalogTypeKey(EanCatalogEntry)} resgata esse caso como
 * CLASSIFICAÇÃO EM CONJUNTO FECHADO (nunca tradução livre):
 * <ol>
 *   <li><b>Cache</b> — {@code resolved_generic_name_norm} já gravado no {@link EanCatalogEntry} →
 *       usa e para (nunca uma 2ª chamada de IA por EAN).</li>
 *   <li><b>Gate</b> — o norm derivado do nome JÁ EXISTE no catálogo → usa e para (nome pt correto
 *       nunca vira mapa/IA; não gasta dinheiro em casamento determinístico).</li>
 *   <li><b>Mapa estático</b> — termos-chave estrangeiros comuns → tipo pt; só aceita se o alvo
 *       EXISTE no catálogo.</li>
 *   <li><b>IA closed-set</b> — {@link AiGateway} (Anthropic) recebe o nome + marca + a LISTA dos
 *       tipos do catálogo COM observações de preço; devolve EXATAMENTE um deles ou o sentinel
 *       {@code NONE}. A resposta é VALIDADA contra o conjunto real — só um membro exato é aceito,
 *       qualquer outra coisa (alucinação/NONE) vira UNRESOLVED (estado vazio honesto, sem invenção).</li>
 * </ol>
 * O resultado (do mapa ou da IA) é gravado no cache numa transação curta e separada — a chamada de
 * IA roda SEM transação. Falha de gravação do cache é best-effort e nunca quebra o lookup.
 */
@Slf4j
@Service
public class ProductTypeResolver {

    /**
     * Mapa curado de termos-chave estrangeiros/ingleses comuns (staples) → nosso {@code generic_name_norm}.
     * As chaves já estão normalizadas (minúsculas, sem acento) — o nome resolvido é comparado contra
     * elas após {@link DescriptionNormalizer#normalizeOrNull}. Só um punhado dos óbvios: o que o OFF
     * costuma devolver em inglês para básicos brasileiros. Um alvo só é aceito se EXISTIR no catálogo.
     */
    private static final Map<String, String> STATIC_FOREIGN_MAP = Map.ofEntries(
            Map.entry("white rice", "arroz"),
            Map.entry("brown rice", "arroz integral"),
            Map.entry("rice", "arroz"),
            Map.entry("milk powder", "leite em po"),
            Map.entry("powdered milk", "leite em po"),
            Map.entry("sugar", "acucar"),
            Map.entry("beans", "feijao"),
            Map.entry("black beans", "feijao"),
            Map.entry("coffee", "cafe"),
            Map.entry("wheat flour", "farinha de trigo"),
            Map.entry("flour", "farinha de trigo"),
            Map.entry("pasta", "macarrao"),
            Map.entry("salt", "sal"),
            Map.entry("soybean oil", "oleo de soja"));

    private static final String AI_SYSTEM =
            "Você é o curador de dados do economizaai, um app brasileiro de preços de supermercado/farmácia. "
            + "O catálogo é em português. Dado o nome de um produto (às vezes em inglês ou ruidoso, vindo do "
            + "Open Food Facts) e uma LISTA de TIPOS de produto válidos (em português), escolha EXATAMENTE UM "
            + "tipo da lista que seja claramente o mesmo produto. Responda SOMENTE com o tipo escolhido, "
            + "copiado LETRA POR LETRA da lista. Se o produto não for claramente um dos tipos listados, "
            + "responda somente NONE. Nunca invente um tipo, nunca traduza para algo fora da lista, nunca "
            + "explique. Uma única palavra/linha: o tipo exato ou NONE.";

    private final ProductExtractor productExtractor;
    private final ProductRepository productRepository;
    private final EanCatalogRepository eanCatalogRepository;
    private final AiGateway aiGateway;
    private final TransactionTemplate cacheTxTemplate;

    public ProductTypeResolver(ProductExtractor productExtractor,
                               ProductRepository productRepository,
                               EanCatalogRepository eanCatalogRepository,
                               AiGateway aiGateway,
                               PlatformTransactionManager transactionManager) {
        this.productExtractor = productExtractor;
        this.productRepository = productRepository;
        this.eanCatalogRepository = eanCatalogRepository;
        this.aiGateway = aiGateway;
        this.cacheTxTemplate = new TransactionTemplate(transactionManager);
        this.cacheTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Chave do tipo ({@code generic_name_norm}) ou null quando não há descrição. */
    public String typeKey(String rawDescription) {
        return DescriptionNormalizer.normalizeOrNull(canonicalGenericName(rawDescription));
    }

    /** {@code genericName} canônico de display (ex.: "Creme de Leite"), ou o nome cru no fallback. */
    public String canonicalGenericName(String rawDescription) {
        if (rawDescription == null || rawDescription.isBlank()) return null;
        var extracted = productExtractor.extract(rawDescription);
        return extracted.genericName() != null ? extracted.genericName() : rawDescription;
    }

    /**
     * Resolve o TIPO de comparação ({@code generic_name_norm}) de uma entrada de catálogo, rodando a
     * cascata cache → gate → mapa estático → IA closed-set (ver doc da classe). Faz a chamada de IA
     * SEM transação e grava o cache numa transação curta separada. Devolve o norm resolvido, ou null
     * quando o produto não tem equivalente claro no catálogo (estado vazio honesto).
     */
    public String resolveCatalogTypeKey(EanCatalogEntry entry) {
        if (entry == null) return null;
        var cached = DescriptionNormalizer.normalizeOrNull(entry.getResolvedGenericNameNorm());
        if (cached != null) return cached;

        var rawName = entry.getGenericName();
        if (rawName == null || rawName.isBlank()) return null;

        // Passo 1 (gate): o tipo derivado do nome já existe no catálogo? Então é um nome pt correto —
        // usa e para, sem mapa nem IA (não gasta dinheiro em casamento determinístico).
        var derived = typeKey(rawName);
        if (derived != null && productRepository.existsByGenericNameNorm(derived)) {
            return derived;
        }

        // Passo 2 (mapa estático): termo estrangeiro comum → tipo pt, só se o alvo existir no catálogo.
        var mapped = resolveFromStaticMap(derived != null ? derived : DescriptionNormalizer.normalizeOrNull(rawName));
        if (mapped != null) {
            log.info("type_resolution.mapped ean={} name=\"{}\" norm={}", entry.getEan(), rawName, mapped);
            cacheResolution(entry.getEan(), mapped);
            return mapped;
        }

        // Passo 3 (IA closed-set): só para o caso órfão. Inerte sem API key → estado vazio de hoje.
        var resolvedByAi = resolveWithAi(rawName, entry.getBrand());
        if (resolvedByAi != null) {
            log.info("type_resolution.ai ean={} name=\"{}\" norm={}", entry.getEan(), rawName, resolvedByAi);
            cacheResolution(entry.getEan(), resolvedByAi);
            return resolvedByAi;
        }
        log.info("type_resolution.unresolved ean={} name=\"{}\"", entry.getEan(), rawName);
        return null;
    }

    private String resolveFromStaticMap(String normalizedName) {
        if (normalizedName == null) return null;
        var target = STATIC_FOREIGN_MAP.get(normalizedName);
        if (target == null) return null;
        return productRepository.existsByGenericNameNorm(target) ? target : null;
    }

    private String resolveWithAi(String rawName, String brand) {
        if (!aiGateway.isEnabled()) return null;
        var validTypes = productRepository.findDistinctGenericNameNormsWithObservations();
        if (validTypes.isEmpty()) return null;
        try {
            var answer = aiGateway.complete(AiActivity.TYPE_RESOLUTION, aiGateway.extractorModel(),
                    AI_SYSTEM, buildAiPrompt(rawName, brand, validTypes), 60);
            return validateAgainstSet(answer, validTypes);
        } catch (RuntimeException aiFailure) {
            // IA indisponível/cap/crédito: resgate best-effort — nunca propaga, volta ao estado vazio.
            log.warn("type_resolution.ai_failed name=\"{}\" reason={}", rawName, aiFailure.getMessage());
            return null;
        }
    }

    private String buildAiPrompt(String rawName, String brand, List<String> validTypes) {
        var types = validTypes.stream().sorted().reduce(new StringBuilder(),
                (builder, type) -> builder.append("- ").append(type).append('\n'),
                StringBuilder::append).toString().strip();
        var brandLine = brand == null || brand.isBlank() ? "" : "\nMARCA: " + brand.strip();
        return """
                PRODUTO: "%s"%s

                TIPOS VÁLIDOS (escolha EXATAMENTE um, copiado letra por letra, ou NONE):
                %s

                Responda somente com o tipo exato da lista ou NONE.""".formatted(rawName.strip(), brandLine, types);
    }

    /** Aceita SÓ um membro exato do conjunto. Alucinação, NONE, vazio → null (unresolved). */
    private String validateAgainstSet(String answer, List<String> validTypes) {
        if (answer == null) return null;
        var cleaned = answer.trim().replaceAll("^\"|\"$", "").trim();
        if (cleaned.isEmpty() || cleaned.equalsIgnoreCase("NONE")) return null;
        var normalized = DescriptionNormalizer.normalizeOrNull(cleaned);
        return validTypes.stream().anyMatch(type -> type.equals(normalized)) ? normalized : null;
    }

    /** Grava o cache numa tx curta e independente. Best-effort — falha não pode quebrar o lookup. */
    private void cacheResolution(String ean, String resolvedNorm) {
        try {
            cacheTxTemplate.executeWithoutResult(status ->
                    eanCatalogRepository.findByEan(ean).ifPresent(entry -> {
                        entry.setResolvedGenericNameNorm(resolvedNorm);
                        eanCatalogRepository.save(entry);
                    }));
        } catch (RuntimeException cacheFailure) {
            log.warn("type_resolution.cache_failed ean={} reason={}", ean, cacheFailure.getMessage());
        }
    }
}
