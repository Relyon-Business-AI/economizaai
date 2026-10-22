package com.relyon.economizaai.model;

import com.relyon.economizaai.model.enums.AiModelTier;
import com.relyon.economizaai.model.enums.AiProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Qual {@code (provider, model)} serve cada tier de IA — editável no admin em
 * runtime (sem redeploy). Uma linha por {@link AiModelTier}. O gateway lê a
 * cada chamada (via cache curto). Seeds iniciais vêm das env vars na migração.
 */
@Entity
@Table(name = "ai_provider_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class AiProviderSetting extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, unique = true)
    private AiModelTier tier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AiProvider provider;

    @Column(nullable = false, length = 80)
    private String model;

    @Column(name = "updated_by", length = 255)
    private String updatedBy;
}
