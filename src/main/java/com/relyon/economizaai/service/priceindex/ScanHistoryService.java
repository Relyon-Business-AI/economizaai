package com.relyon.economizaai.service.priceindex;

import com.relyon.economizaai.dto.response.ScannedProductResponse;
import com.relyon.economizaai.model.HouseholdProductSnapshot;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.HouseholdProductSnapshotRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.HouseholdProductAliasService;
import com.relyon.economizaai.time.BrazilClock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Histórico por domicílio dos produtos consultados, com um snapshot do melhor preço no momento
 * da consulta. A gravação ({@link #recordConsult}) é best-effort, numa tx curta e independente
 * (REQUIRES_NEW) — NUNCA segura transação durante nada lento e NUNCA propaga falha (é chamada
 * do caminho de leitura da comparação / do log de scan, que não pode quebrar).
 *
 * <p>A listagem ({@link #listScannedProducts}) devolve SÓ o snapshot gravado — instantâneo, sem
 * recálculo ao vivo e sem N+1. O FE busca o preço ao vivo por item visível (endpoints de
 * comparação existentes) e calcula o delta no cliente.
 */
@Slf4j
@Service
public class ScanHistoryService {

    /** Teto de itens no histórico (dedupado por produto/tipo, consulta mais recente primeiro). */
    private static final int HISTORY_LIMIT = 100;

    private final HouseholdProductSnapshotRepository snapshotRepository;
    private final ProductRepository productRepository;
    private final HouseholdProductAliasService householdProductAliasService;
    private final TransactionTemplate snapshotTxTemplate;

    public ScanHistoryService(HouseholdProductSnapshotRepository snapshotRepository,
                              ProductRepository productRepository,
                              HouseholdProductAliasService householdProductAliasService,
                              PlatformTransactionManager transactionManager) {
        this.snapshotRepository = snapshotRepository;
        this.productRepository = productRepository;
        this.householdProductAliasService = householdProductAliasService;
        this.snapshotTxTemplate = new TransactionTemplate(transactionManager);
        this.snapshotTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Upsert do snapshot quando uma comparação é SERVIDA (ou um scan logado) pra o domicílio:
     * captura o melhor preço + escopo do momento. Prefere {@code productId}; senão o tipo
     * ({@code genericNameNorm}). Swallows tudo — não pode escapar e quebrar o caminho de leitura.
     */
    public void recordConsult(UUID householdId, UUID productId, String genericNameNorm,
                              BigDecimal bestUnitPrice, String bestScope) {
        var snapshotKey = HouseholdProductSnapshot.keyFor(productId, genericNameNorm);
        if (householdId == null || snapshotKey == null) return;
        try {
            snapshotTxTemplate.executeWithoutResult(status ->
                    upsert(householdId, productId, genericNameNorm, snapshotKey, bestUnitPrice, bestScope));
            log.debug("scan_history.recorded household={} key={} price={} scope={}",
                    householdId, snapshotKey, bestUnitPrice, bestScope);
        } catch (Exception snapshotFailure) {
            log.warn("scan_history.record_failed household={} key={} reason={}",
                    householdId, snapshotKey, snapshotFailure.toString());
        }
    }

    private void upsert(UUID householdId, UUID productId, String genericNameNorm, String snapshotKey,
                        BigDecimal bestUnitPrice, String bestScope) {
        var now = BrazilClock.nowOffset();
        var snapshot = snapshotRepository.findByHouseholdIdAndSnapshotKey(householdId, snapshotKey)
                .orElseGet(() -> HouseholdProductSnapshot.builder()
                        .householdId(householdId)
                        .productId(productId)
                        .genericNameNorm(genericNameNorm)
                        .snapshotKey(snapshotKey)
                        .scanCount(0)
                        .build());
        // A chave pode ter sido criada só pelo tipo e depois resolver um produto — mantém o mais específico.
        if (snapshot.getProductId() == null && productId != null) snapshot.setProductId(productId);
        if (genericNameNorm != null) snapshot.setGenericNameNorm(genericNameNorm);
        // O log de scan grava SEM preço (price=null): nesse caso NÃO sobrescreve um preço já
        // capturado por uma comparação servida — só a consulta com preço atualiza o snapshot de preço.
        if (bestUnitPrice != null) {
            snapshot.setLastBestUnitPrice(bestUnitPrice);
            snapshot.setLastBestScope(bestScope);
        }
        snapshot.setScanCount(snapshot.getScanCount() + 1);
        snapshot.setLastConsultedAt(now);
        snapshotRepository.save(snapshot);
    }

    /**
     * Histórico do domicílio: produtos consultados, dedupados por produto/tipo (a tabela já é
     * única por chave), consulta mais recente primeiro, limitado a {@value #HISTORY_LIMIT}.
     * SÓ o snapshot gravado — sem preço ao vivo aqui (o FE busca por item).
     */
    @Transactional(readOnly = true)
    public List<ScannedProductResponse> listScannedProducts(User user) {
        var snapshots = snapshotRepository.findByHouseholdIdOrderByLastConsultedAtDesc(
                user.getHousehold().getId(), PageRequest.of(0, HISTORY_LIMIT));
        if (snapshots.isEmpty()) return List.of();
        var productsById = loadProducts(snapshots);
        return snapshots.stream()
                .map(snapshot -> toResponse(user, snapshot, productsById.get(snapshot.getProductId())))
                .toList();
    }

    private Map<UUID, Product> loadProducts(List<HouseholdProductSnapshot> snapshots) {
        var productIds = snapshots.stream()
                .map(HouseholdProductSnapshot::getProductId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (productIds.isEmpty()) return Map.of();
        return productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, product -> product));
    }

    private ScannedProductResponse toResponse(User user, HouseholdProductSnapshot snapshot, Product product) {
        var typeLabel = product != null ? product.getGenericName() : null;
        if (typeLabel == null) typeLabel = snapshot.getGenericNameNorm();
        var productName = product == null ? typeLabel
                : householdProductAliasService.findFor(user.getHousehold(), product);
        if (productName == null && product != null) productName = product.getNormalizedName();
        return new ScannedProductResponse(
                snapshot.getProductId(),
                productName,
                typeLabel,
                snapshot.getLastConsultedAt(),
                snapshot.getLastBestUnitPrice(),
                snapshot.getLastBestScope(),
                snapshot.getScanCount());
    }
}
