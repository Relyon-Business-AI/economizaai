package com.relyon.economizaai.service.ai;

import com.relyon.economizaai.model.AiProviderSetting;
import com.relyon.economizaai.model.enums.AiModelTier;
import com.relyon.economizaai.model.enums.AiProvider;
import com.relyon.economizaai.repository.AiProviderSettingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Qual {@code (provider, model)} serve cada tier, lido em runtime. Cache em
 * memória invalidado no update — o gateway chama isto a cada AI call, não pode
 * bater no banco toda vez. Defaults das env vars se a linha não existir (a
 * migração V116 semeia, mas isto garante robustez).
 */
@Slf4j
@Service
public class AiProviderSettingsService {

    public record TierConfig(AiProvider provider, String model) {}

    private final AiProviderSettingRepository repository;
    private final TierConfig extractorDefault;
    private final TierConfig curatorDefault;
    private final Map<AiModelTier, TierConfig> cache = new ConcurrentHashMap<>();

    public AiProviderSettingsService(
            AiProviderSettingRepository repository,
            @Value("${economizaai.ai.extractor-model:claude-haiku-4-5}") String extractorModel,
            @Value("${economizaai.ai.curator-model:claude-sonnet-4-6}") String curatorModel) {
        this.repository = repository;
        this.extractorDefault = new TierConfig(AiProvider.ANTHROPIC, extractorModel);
        this.curatorDefault = new TierConfig(AiProvider.ANTHROPIC, curatorModel);
    }

    public TierConfig config(AiModelTier tier) {
        return cache.computeIfAbsent(tier, this::load);
    }

    private TierConfig load(AiModelTier tier) {
        return repository.findByTier(tier)
                .map(setting -> new TierConfig(setting.getProvider(), setting.getModel()))
                .orElseGet(() -> tier == AiModelTier.CURATOR ? curatorDefault : extractorDefault);
    }

    @Transactional
    public void update(AiModelTier tier, AiProvider provider, String model, String updatedBy) {
        var setting = repository.findByTier(tier).orElseGet(() -> AiProviderSetting.builder().tier(tier).build());
        setting.setProvider(provider);
        setting.setModel(model.trim());
        setting.setUpdatedBy(updatedBy);
        repository.save(setting);
        cache.put(tier, new TierConfig(provider, model.trim()));
        log.info("ai_provider.updated tier={} provider={} model={} by={}", tier, provider, model, updatedBy);
    }
}
