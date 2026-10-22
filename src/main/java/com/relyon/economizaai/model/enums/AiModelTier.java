package com.relyon.economizaai.model.enums;

/**
 * The two logical roles every AI call falls into — the admin picks a
 * {@code (provider, model)} per tier, so "GPT para volume, Claude para
 * julgamento" (ou qualquer combinação) é um toggle, não um deploy.
 */
public enum AiModelTier {
    /** Alto volume / simples: categorização no scan, sugestões, visão da foto. */
    EXTRACTOR,
    /** Julgamento: categoria fina (gabarito), juiz de duplicata/consenso. */
    CURATOR
}
