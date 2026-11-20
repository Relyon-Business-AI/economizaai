package com.relyon.economizaai.service.settings;

import com.relyon.economizaai.service.ai.AiCategoryConfidence;
import com.relyon.economizaai.service.canonicalization.CanonicalizationService;
import com.relyon.economizaai.service.extraction.AutoPromotionService;
import com.relyon.economizaai.service.extraction.ConsensusPromotionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CategorizationSettingsBinderTest {

    private final AiCategoryConfidence aiConfidence = new AiCategoryConfidence();
    private final AutoPromotionService autoPromotion = new AutoPromotionService(null, null, null, null, null);
    private final ConsensusPromotionService consensusPromotion =
            new ConsensusPromotionService(null, null, null, null, null, null, null);
    private final CanonicalizationService canonicalization =
            new CanonicalizationService(null, null, null, null, null, null, null, null);
    private final SettingBindings bindings = new SettingBindings();

    @BeforeEach
    void bind() {
        new CategorizationSettingsBinder(aiConfidence, autoPromotion, consensusPromotion, canonicalization).bind(bindings);
    }

    private String valueOf(RuntimeSetting setting) {
        return bindings.of(setting).currentValue().get();
    }

    private void change(RuntimeSetting setting, String value) {
        bindings.of(setting).replaceValue().accept(value);
    }

    @Test
    void theAiConfidenceAndTheSimilarity_startAtTheValuesInUseToday() {
        assertThat(valueOf(RuntimeSetting.AI_SET_MIN_CONFIDENCE)).isEqualTo("0.55");
        assertThat(valueOf(RuntimeSetting.AI_CHANGE_SUBCATEGORY_MIN_CONFIDENCE)).isEqualTo("0.8");
        assertThat(valueOf(RuntimeSetting.AI_CHANGE_CATEGORY_MIN_CONFIDENCE)).isEqualTo("0.9");
        assertThat(valueOf(RuntimeSetting.CANONICAL_MATCH_SIMILARITY)).isEqualTo("0.85");
    }

    @Test
    void aSavedValue_reachesTheCodeThatUsesIt() {
        change(RuntimeSetting.AI_SET_MIN_CONFIDENCE, "0.6");
        change(RuntimeSetting.AI_CHANGE_SUBCATEGORY_MIN_CONFIDENCE, "0.75");
        change(RuntimeSetting.AI_CHANGE_CATEGORY_MIN_CONFIDENCE, "0.95");
        change(RuntimeSetting.AUTO_PROMOTE_MIN_SAMPLES, "12");
        change(RuntimeSetting.AUTO_PROMOTE_MIN_AGREEMENT, "0.8");
        change(RuntimeSetting.CONSENSUS_MIN_HOUSEHOLDS, "4");
        change(RuntimeSetting.CONSENSUS_MIN_TOKEN_PRODUCTS, "5");
        change(RuntimeSetting.CANONICAL_MATCH_SIMILARITY, "0.9");

        assertThat(aiConfidence.getSetMinConfidence()).isEqualTo(0.6);
        assertThat(aiConfidence.getChangeSubcategoryMinConfidence()).isEqualTo(0.75);
        assertThat(aiConfidence.getChangeCategoryMinConfidence()).isEqualTo(0.95);
        assertThat(autoPromotion.getMinSamples()).isEqualTo(12);
        assertThat(autoPromotion.getMinAgreement()).isEqualTo(0.8);
        assertThat(consensusPromotion.getMinHouseholds()).isEqualTo(4);
        assertThat(consensusPromotion.getMinTokenProducts()).isEqualTo(5);
        assertThat(canonicalization.getFuzzyMatchThreshold()).isEqualTo(0.9);
    }
}
