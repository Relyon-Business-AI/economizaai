package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.RuntimeSettingOverride;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RuntimeSettingOverrideRepository extends JpaRepository<RuntimeSettingOverride, UUID> {
    Optional<RuntimeSettingOverride> findBySettingKey(String settingKey);
}
