package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.AiProviderSetting;
import com.relyon.economizaai.model.enums.AiModelTier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AiProviderSettingRepository extends JpaRepository<AiProviderSetting, UUID> {
    Optional<AiProviderSetting> findByTier(AiModelTier tier);
}
