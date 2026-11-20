package com.relyon.economizaai.service.ai;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * How sure the AI must be before a category suggestion is kept, by how risky the action is:
 * filling an empty subcategory asks the least, replacing an existing one asks more, moving the
 * product to another CATEGORY asks the most. One source for the admin sweep and for the
 * suggestions made when a receipt comes in; changed in the admin settings.
 */
@Getter
@Setter
@Component
public class AiCategoryConfidence {

    @Value("${economizaai.ai.subcategory-suggestion.set-min-confidence:0.55}")
    private volatile double setMinConfidence = 0.55;

    @Value("${economizaai.ai.subcategory-suggestion.change-subcategory-min-confidence:0.80}")
    private volatile double changeSubcategoryMinConfidence = 0.80;

    @Value("${economizaai.ai.subcategory-suggestion.change-category-min-confidence:0.90}")
    private volatile double changeCategoryMinConfidence = 0.90;
}
