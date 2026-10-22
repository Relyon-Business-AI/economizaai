package com.relyon.economizaai.service.ai;

import com.relyon.economizaai.model.AiProviderSetting;
import com.relyon.economizaai.model.enums.AiModelTier;
import com.relyon.economizaai.model.enums.AiProvider;
import com.relyon.economizaai.repository.AiProviderSettingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiProviderSettingsServiceTest {

    @Mock private AiProviderSettingRepository repository;

    private AiProviderSettingsService service() {
        return new AiProviderSettingsService(repository, "claude-haiku-4-5", "claude-sonnet-4-6");
    }

    @Test
    void config_readsFromDb() {
        when(repository.findByTier(AiModelTier.CURATOR)).thenReturn(Optional.of(
                AiProviderSetting.builder().tier(AiModelTier.CURATOR).provider(AiProvider.OPENAI).model("gpt-5").build()));

        var config = service().config(AiModelTier.CURATOR);

        assertEquals(AiProvider.OPENAI, config.provider());
        assertEquals("gpt-5", config.model());
    }

    @Test
    void config_fallsBackToEnvDefaultWhenRowMissing() {
        when(repository.findByTier(AiModelTier.EXTRACTOR)).thenReturn(Optional.empty());

        var config = service().config(AiModelTier.EXTRACTOR);

        assertEquals(AiProvider.ANTHROPIC, config.provider());
        assertEquals("claude-haiku-4-5", config.model());
    }

    @Test
    void config_isCached_doesNotHitDbTwice() {
        when(repository.findByTier(AiModelTier.CURATOR)).thenReturn(Optional.empty());
        var service = service();

        service.config(AiModelTier.CURATOR);
        service.config(AiModelTier.CURATOR);

        verify(repository, org.mockito.Mockito.times(1)).findByTier(AiModelTier.CURATOR);
    }

    @Test
    void update_persistsAndRefreshesCache() {
        when(repository.findByTier(AiModelTier.EXTRACTOR)).thenReturn(Optional.empty());
        var service = service();

        service.update(AiModelTier.EXTRACTOR, AiProvider.OPENAI, "gpt-5-nano ", "admin@economizaai.app");

        verify(repository).save(any(AiProviderSetting.class));
        // cache já reflete o novo valor (trim aplicado); config() lê do cache, sem novo hit ao banco
        var config = service.config(AiModelTier.EXTRACTOR);
        assertEquals(AiProvider.OPENAI, config.provider());
        assertEquals("gpt-5-nano", config.model());
        // findByTier só foi chamado 1× (dentro do update pra find-or-create), não pelo config()
        verify(repository, org.mockito.Mockito.times(1)).findByTier(AiModelTier.EXTRACTOR);
    }
}
