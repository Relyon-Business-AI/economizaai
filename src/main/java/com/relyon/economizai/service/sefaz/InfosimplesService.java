package com.relyon.economizai.service.sefaz;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.relyon.economizai.exception.ReceiptParseException;
import com.relyon.economizai.model.enums.UnidadeFederativa;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Fallback SEFAZ data provider backed by the Infosimples paid API
 * (https://api.infosimples.com). Returns a {@link ParsedReceipt} directly from
 * JSON — no HTML scraping or captcha solving involved.
 *
 * <p>Only active when {@code economizai.infosimples.enabled=true}
 * ({@code INFOSIMPLES_ENABLED=true} env var). Cost is ~R$0.24 per query so
 * this is intentionally a last resort, called only after the primary scraper
 * exhausts its retries.
 *
 * <p>Most UFs use the {@code /api/v2/consultas/sefaz/{uf}/nfce} endpoint — the UF
 * code is lowercased from the chave's IBGE prefix (50→ms, 43→rs, etc.). A few UFs
 * (MG) are only exposed under {@code .../{uf}/nfce-resumida} and return a different
 * "resumida" JSON schema (produtos_servicos/valores); those UFs are listed in
 * {@code economizai.infosimples.resumida-states} and handled by the same mapping.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "economizai.infosimples", name = "enabled", havingValue = "true")
public class InfosimplesService {

    private static final DateTimeFormatter DATE_TIME_FMT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final ParameterizedTypeReference<InfosimplesResponse> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private final String apiKey;
    private final RestClient restClient;
    // UFs whose Infosimples NFC-e consultation lives at the `.../sefaz/{uf}/nfce-resumida`
    // slug and returns the "resumida" schema (produtos_servicos/valores) rather than the
    // default `.../sefaz/{uf}/nfce`. MG is the only one verified so far — the default
    // path returns code 602 "serviço informado na URL não é válido" for it.
    private final Set<UnidadeFederativa> resumidaStates;

    public InfosimplesService(
            RestClient.Builder builder,
            @Value("${economizai.infosimples.api-key}") String apiKey,
            @Value("${economizai.infosimples.base-url:https://api.infosimples.com}") String baseUrl,
            @Value("${economizai.infosimples.resumida-states:MG}") String resumidaStatesCsv) {
        this.apiKey = apiKey;
        this.restClient = builder.baseUrl(baseUrl).build();
        this.resumidaStates = parseStates(resumidaStatesCsv);
        log.info("infosimples.service enabled base-url={} resumida-states={}", baseUrl, resumidaStates);
    }

    private static Set<UnidadeFederativa> parseStates(String csv) {
        var states = EnumSet.noneOf(UnidadeFederativa.class);
        if (csv == null || csv.isBlank()) return states;
        Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .forEach(token -> {
                    try {
                        states.add(UnidadeFederativa.valueOf(token.toUpperCase()));
                    } catch (IllegalArgumentException ignored) {
                        log.warn("infosimples.resumida-states.ignored token='{}'", token);
                    }
                });
        return states;
    }

    /**
     * Calls the Infosimples SEFAZ NFC-e endpoint for the given chave and UF,
     * then maps the JSON response to a {@link ParsedReceipt}.
     *
     * @throws ReceiptParseException if Infosimples returns a non-200 code or
     *                               the data array is empty.
     */
    public ParsedReceipt fetchParsed(String chave, UnidadeFederativa uf) {
        var ufCode = uf.name().toLowerCase();
        var resource = resumidaStates.contains(uf) ? "nfce-resumida" : "nfce";
        log.info("infosimples.fetch chave={} uf={} resource={}", abbrev(chave), ufCode, resource);
        var response = restClient.get()
                .uri("/api/v2/consultas/sefaz/{uf}/{resource}?token={token}&nfce={nfce}",
                        ufCode, resource, apiKey, chave)
                .retrieve()
                .body(RESPONSE_TYPE);

        if (response == null || response.code() != 200) {
            var code = response == null ? -1 : response.code();
            var message = response == null ? null : response.codeMessage();
            var errors = response == null ? null : response.errors();
            log.warn("infosimples.fetch.failed chave={} uf={} code={} message='{}' errors={}",
                    abbrev(chave), ufCode, code, message, errors);
            throw new ReceiptParseException("infosimples.error");
        }
        if (response.data() == null || response.data().isEmpty()) {
            log.warn("infosimples.fetch.empty chave={} uf={} message='{}'",
                    abbrev(chave), ufCode, response.codeMessage());
            throw new ReceiptParseException("infosimples.empty-response");
        }
        log.info("infosimples.fetch.ok chave={} uf={}", abbrev(chave), ufCode);
        return toParsedReceipt(chave, response.data().get(0));
    }

    private ParsedReceipt toParsedReceipt(String chave, InfosimplesData data) {
        var emitente = data.emitente();
        var totais = data.totais();
        var nfe = data.nfe();

        var valores = data.valores();
        var cnpj = firstNonBlank(
                emitente == null ? null : emitente.normalizadoCnpj(),
                emitente == null || emitente.cnpj() == null ? null : emitente.cnpj().replaceAll("\\D", ""));
        // Market name: "resumida" (MG) uses razao_social; other resumida uses
        // nome_razao_social; "completa" uses nome.
        var marketName = emitente == null ? null
                : firstNonBlank(emitente.nomeRazaoSocial(), emitente.nome(), emitente.razaoSocial());
        var marketAddress = emitente == null ? null : emitente.endereco();
        var issuedAt = parseIssuedAt(data.informacoesNota(), nfe, data.nfce());
        // Total: MG resumida nests it under valores; other resumida carries it
        // top-level; "completa" nests it under totais/nfe.
        var total = firstNonNull(
                data.normalizadoValorAPagar(),
                valores == null ? null : valores.normalizadoValorTotalServico(),
                totais == null ? null : totais.normalizadoValorNfe(),
                nfe == null ? null : nfe.normalizadoValorTotal());
        var discount = firstNonNull(
                data.normalizadoValorDesconto(),
                totais == null ? null : totais.normalizadoValorDescontos());
        var items = toItems(firstNonNull(data.produtos(), data.produtosServicos()));

        return ParsedReceipt.builder()
                .chaveAcesso(chave)
                .cnpjEmitente(cnpj)
                .marketName(marketName)
                .marketAddress(marketAddress)
                .issuedAt(issuedAt)
                .totalAmount(total)
                .discountTotal(discount)
                .approxTaxFederal(null)
                .approxTaxEstadual(null)
                .sourceUrl(data.siteReceipt())
                .rawHtml(null)
                .items(items)
                .build();
    }

    /**
     * Emission timestamp across both response shapes: the "resumida" shape splits
     * it into {@code informacoes_nota.data_emissao} + {@code hora_emissao}; the
     * "completa" shape carries a single {@code nfe.data_emissao} like
     * {@code "05/07/2026 14:11:14-03:00"} (trailing tz offset stripped).
     */
    private static LocalDateTime parseIssuedAt(InfosimplesNotaInfo nota, InfosimplesNfe nfe,
                                               InfosimplesNfceBlock nfceBlock) {
        if (nota != null && nota.dataEmissao() != null && nota.horaEmissao() != null) {
            var parsed = tryParse(nota.dataEmissao() + " " + nota.horaEmissao());
            if (parsed != null) return parsed;
        }
        // MG resumida: nfce.data_emissao is a full "dd/MM/yyyy HH:mm:ss".
        if (nfceBlock != null && nfceBlock.dataEmissao() != null && !nfceBlock.dataEmissao().isBlank()) {
            var parsed = tryParse(nfceBlock.dataEmissao().trim());
            if (parsed != null) return parsed;
        }
        if (nfe != null && nfe.dataEmissao() != null && !nfe.dataEmissao().isBlank()) {
            // Strip a trailing "-03:00" style offset, keep "dd/MM/yyyy HH:mm:ss".
            var head = nfe.dataEmissao().trim().replaceAll("([+-]\\d{2}:?\\d{2})$", "").trim();
            return tryParse(head);
        }
        return null;
    }

    private static LocalDateTime tryParse(String value) {
        try {
            return LocalDateTime.parse(value.trim(), DATE_TIME_FMT);
        } catch (Exception ex) {
            log.warn("infosimples.parse.date-failed value='{}'", value);
            return null;
        }
    }

    private static List<ParsedReceiptItem> toItems(List<InfosimplesProduto> produtos) {
        if (produtos == null) return List.of();
        var lineNumber = new int[]{1};
        return produtos.stream().map(produto -> {
            // "resumida" uses nome/normalizado_quantidade/normalizado_valor_total_produto;
            // "completa" uses descricao/qtd/normalizado_valor; MG resumida uses
            // descricao/quantidade/normalizado_valor. quantity/unitPrice/totalPrice map
            // to NOT NULL columns — default defensively.
            var rawQty = firstNonNull(produto.normalizadoQuantidade(), produto.qtd(), produto.quantidade());
            var quantity = rawQty == null ? BigDecimal.ONE : BigDecimal.valueOf(rawQty);
            var totalPrice = firstNonNull(
                    produto.normalizadoValorTotalProduto(), produto.normalizadoValor());
            var unitPrice = produto.normalizadoValorUnitario();
            if (unitPrice == null) {
                unitPrice = totalPrice == null || quantity.signum() == 0
                        ? BigDecimal.ZERO
                        : totalPrice.divide(quantity, 2, RoundingMode.HALF_UP);
            }
            if (totalPrice == null) {
                totalPrice = unitPrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
            }
            return ParsedReceiptItem.builder()
                    .lineNumber(lineNumber[0]++)
                    .rawDescription(firstNonBlank(produto.nome(), produto.descricao()))
                    // Persist the barcode the merchant declared (packaged items carry it;
                    // loose produce/meat sold by weight report "SEM GTIN"). It drives the
                    // EAN-catalog category lookup + cross-receipt product dedup, so we keep
                    // it even when today's catalog has no entry for it.
                    .ean(extractGtin(produto.eanTributavel(), produto.eanComercial()))
                    .quantity(quantity)
                    .unit(UnitNormalizer.normalize(firstNonBlank(produto.unidade(), produto.unidadeComercial())))
                    .unitPrice(unitPrice)
                    .totalPrice(totalPrice)
                    .nfcePromoFlag(false)
                    .build();
        }).toList();
    }

    private static String abbrev(String chave) {
        return chave == null || chave.length() < 8 ? chave : chave.substring(0, 8);
    }

    /**
     * Normalizes a merchant-declared GTIN to the EAN-13 form the catalog stores.
     * Infosimples reports the tributável GTIN as 13 digits ("7891000098950") and
     * the comercial one zero-padded to 14 ("07891000098950"); the catalog is keyed
     * by the 13-digit form, so a leading pad zero is dropped. "SEM GTIN" / blank /
     * sub-8-digit codes (merchant-internal PLUs) are not real barcodes → null.
     */
    static String extractGtin(String... candidates) {
        for (var candidate : candidates) {
            if (candidate == null) continue;
            var digits = candidate.replaceAll("\\D", "");
            if (digits.length() < 8) continue;
            if (digits.length() == 14 && digits.charAt(0) == '0') {
                digits = digits.substring(1);
            }
            return digits;
        }
        return null;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (var value : values) {
            if (value != null) return value;
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (var value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    // ── Internal JSON DTOs ─────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    record InfosimplesResponse(
            int code,
            @JsonProperty("code_message") String codeMessage,
            List<String> errors,
            List<InfosimplesData> data
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record InfosimplesData(
            InfosimplesEmitente emitente,
            @JsonProperty("informacoes_nota") InfosimplesNotaInfo informacoesNota,
            InfosimplesTotais totais,
            InfosimplesNfe nfe,
            @JsonProperty("normalizado_valor_a_pagar") BigDecimal normalizadoValorAPagar,
            @JsonProperty("normalizado_valor_desconto") BigDecimal normalizadoValorDesconto,
            List<InfosimplesProduto> produtos,
            @JsonProperty("site_receipt") String siteReceipt,
            // "resumida" shape (MG /nfce-resumida): items live under produtos_servicos,
            // totals under valores, emission datetime under the nfce block.
            @JsonProperty("produtos_servicos") List<InfosimplesProduto> produtosServicos,
            InfosimplesValores valores,
            InfosimplesNfceBlock nfce
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record InfosimplesEmitente(
            String cnpj,
            @JsonProperty("normalizado_cnpj") String normalizadoCnpj,
            String endereco,
            String nome,
            @JsonProperty("nome_razao_social") String nomeRazaoSocial,
            // "resumida" shape carries the market name under razao_social.
            @JsonProperty("razao_social") String razaoSocial
    ) {}

    /** "resumida" shape totals block (MG). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record InfosimplesValores(
            @JsonProperty("normalizado_valor_total_servico") BigDecimal normalizadoValorTotalServico
    ) {}

    /** "resumida" shape nfce block — carries the emission datetime. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record InfosimplesNfceBlock(
            @JsonProperty("data_emissao") String dataEmissao
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record InfosimplesNotaInfo(
            @JsonProperty("data_emissao") String dataEmissao,
            @JsonProperty("hora_emissao") String horaEmissao
    ) {}

    /** "completa" shape totals block. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record InfosimplesTotais(
            @JsonProperty("normalizado_valor_nfe") BigDecimal normalizadoValorNfe,
            @JsonProperty("normalizado_valor_descontos") BigDecimal normalizadoValorDescontos
    ) {}

    /** "completa" shape nfe block (single-field emission datetime + total). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record InfosimplesNfe(
            @JsonProperty("data_emissao") String dataEmissao,
            @JsonProperty("normalizado_valor_total") BigDecimal normalizadoValorTotal
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record InfosimplesProduto(
            String nome,
            String descricao,
            @JsonProperty("normalizado_quantidade") Double normalizadoQuantidade,
            Double qtd,
            // "resumida" shape uses a plain `quantidade` + `unidade_comercial`.
            Double quantidade,
            @JsonProperty("normalizado_valor_unitario") BigDecimal normalizadoValorUnitario,
            @JsonProperty("normalizado_valor_total_produto") BigDecimal normalizadoValorTotalProduto,
            @JsonProperty("normalizado_valor") BigDecimal normalizadoValor,
            @JsonProperty("ean_comercial") String eanComercial,
            @JsonProperty("ean_tributavel") String eanTributavel,
            String unidade,
            @JsonProperty("unidade_comercial") String unidadeComercial
    ) {}
}
