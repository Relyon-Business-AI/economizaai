package com.relyon.economizaai.service.settings;

import com.relyon.economizaai.service.ai.AiCategoryConfidence;
import com.relyon.economizaai.service.canonicalization.CanonicalizationService;
import com.relyon.economizaai.service.extraction.AutoPromotionService;
import com.relyon.economizaai.service.extraction.ConsensusPromotionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Product categorization: how sure the AI must be, when the dictionary learns and when two descriptions are one product. */
@Component
@RequiredArgsConstructor
public class CategorizationSettingsBinder implements SettingBinder {

    private final AiCategoryConfidence aiConfidence;
    private final AutoPromotionService autoPromotion;
    private final ConsensusPromotionService consensusPromotion;
    private final CanonicalizationService canonicalization;

    @Override
    public void bind(SettingBindings bindings) {
        bindings.fraction(RuntimeSetting.AI_SET_MIN_CONFIDENCE,
                aiConfidence::getSetMinConfidence, aiConfidence::setSetMinConfidence);
        bindings.fraction(RuntimeSetting.AI_CHANGE_SUBCATEGORY_MIN_CONFIDENCE,
                aiConfidence::getChangeSubcategoryMinConfidence, aiConfidence::setChangeSubcategoryMinConfidence);
        bindings.fraction(RuntimeSetting.AI_CHANGE_CATEGORY_MIN_CONFIDENCE,
                aiConfidence::getChangeCategoryMinConfidence, aiConfidence::setChangeCategoryMinConfidence);
        bindings.integer(RuntimeSetting.AUTO_PROMOTE_MIN_SAMPLES, autoPromotion::getMinSamples, autoPromotion::setMinSamples);
        bindings.fraction(RuntimeSetting.AUTO_PROMOTE_MIN_AGREEMENT,
                autoPromotion::getMinAgreement, autoPromotion::setMinAgreement);
        bindings.integer(RuntimeSetting.CONSENSUS_MIN_HOUSEHOLDS,
                consensusPromotion::getMinHouseholds, consensusPromotion::setMinHouseholds);
        bindings.integer(RuntimeSetting.CONSENSUS_MIN_TOKEN_PRODUCTS,
                consensusPromotion::getMinTokenProducts, consensusPromotion::setMinTokenProducts);
        bindings.fraction(RuntimeSetting.CANONICAL_MATCH_SIMILARITY,
                canonicalization::getFuzzyMatchThreshold, canonicalization::setFuzzyMatchThreshold);
    }
}
