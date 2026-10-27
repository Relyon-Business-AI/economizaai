package com.relyon.economizaai.service.admin;

import com.relyon.economizaai.dto.request.CreateMarketChainRequest;
import com.relyon.economizaai.dto.request.UpdateMarketChainRequest;
import com.relyon.economizaai.dto.response.MarketChainResponse;
import com.relyon.economizaai.model.MarketChain;
import com.relyon.economizaai.repository.MarketChainRepository;
import com.relyon.economizaai.repository.MarketLocationRepository;
import com.relyon.economizaai.service.chain.ChainLogoStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminMarketChainService {

    private static final long MAX_LOGO_SIZE = 2 * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/svg+xml");

    private final MarketChainRepository chainRepository;
    private final MarketLocationRepository marketLocationRepository;
    private final ChainLogoStorage logoStorage;

    @Transactional(readOnly = true)
    public Page<MarketChainResponse> listAll(String state, String search, Pageable pageable) {
        return chainRepository.findAllFiltered(state, search, pageable)
                .map(row -> {
                    var id = (UUID) row[0];
                    var cnpjRoot = (String) row[1];
                    var name = (String) row[2];
                    var logoUrlKey = (String) row[3];
                    var marketCount = ((Number) row[4]).intValue();
                    String logoUrl = logoUrlKey != null ? "/chains/logo/" + id : null;
                    return new MarketChainResponse(id, cnpjRoot, name, logoUrl, marketCount);
                });
    }

    @Transactional(readOnly = true)
    public List<String> listStates() {
        return chainRepository.findDistinctStatesWithChains();
    }

    @Transactional
    public MarketChainResponse create(CreateMarketChainRequest request) {
        if (chainRepository.findByCnpjRoot(request.cnpjRoot()).isPresent()) {
            throw new IllegalArgumentException("Chain already exists for cnpjRoot " + request.cnpjRoot());
        }
        var chain = MarketChain.builder()
                .cnpjRoot(request.cnpjRoot())
                .name(request.name())
                .build();
        var saved = chainRepository.save(chain);
        linkOrphanedMarkets(saved);
        log.info("market_chain.created id={} cnpjRoot={} name='{}'", saved.getId(), saved.getCnpjRoot(), saved.getName());
        return MarketChainResponse.from(saved, marketLocationRepository.countByChain(saved));
    }

    @Transactional
    public MarketChainResponse update(UUID id, UpdateMarketChainRequest request) {
        var chain = chainRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Chain not found: " + id));
        chain.setName(request.name());
        var saved = chainRepository.save(chain);
        log.info("market_chain.updated id={} name='{}'", saved.getId(), saved.getName());
        return MarketChainResponse.from(saved, marketLocationRepository.countByChain(saved));
    }

    @Transactional
    public MarketChainResponse uploadLogo(UUID id, MultipartFile file) throws IOException {
        var chain = chainRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Chain not found: " + id));
        validateLogoFile(file);
        if (chain.getLogoUrl() != null) {
            logoStorage.delete(chain.getLogoUrl());
        }
        var key = logoStorage.store(file.getInputStream(), file.getContentType(), file.getSize());
        chain.setLogoUrl(key);
        var saved = chainRepository.save(chain);
        log.info("market_chain.logo_uploaded id={} key={}", id, key);
        return MarketChainResponse.from(saved, marketLocationRepository.countByChain(saved));
    }

    @Transactional
    public void deleteLogo(UUID id) throws IOException {
        var chain = chainRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Chain not found: " + id));
        if (chain.getLogoUrl() != null) {
            logoStorage.delete(chain.getLogoUrl());
            chain.setLogoUrl(null);
            chainRepository.save(chain);
            log.info("market_chain.logo_deleted id={}", id);
        }
    }

    public byte[] readLogo(UUID id) throws IOException {
        var chain = chainRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Chain not found: " + id));
        if (chain.getLogoUrl() == null) return null;
        return logoStorage.read(chain.getLogoUrl());
    }

    private void linkOrphanedMarkets(MarketChain chain) {
        var orphans = marketLocationRepository.findAllByCnpjRootAndChainIsNull(chain.getCnpjRoot());
        for (var market : orphans) {
            market.setChain(chain);
        }
        if (!orphans.isEmpty()) {
            marketLocationRepository.saveAll(orphans);
            log.info("market_chain.linked_orphans chainId={} count={}", chain.getId(), orphans.size());
        }
    }

    private void validateLogoFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Logo file is empty");
        }
        if (file.getSize() > MAX_LOGO_SIZE) {
            throw new IllegalArgumentException("Logo file exceeds 2MB limit");
        }
        if (!ALLOWED_TYPES.contains(file.getContentType())) {
            throw new IllegalArgumentException("Unsupported image type: " + file.getContentType());
        }
    }
}
