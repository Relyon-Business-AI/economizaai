package com.relyon.economizaai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Marketing attribution the FE reads off the landing URL (query params +
 * document.referrer) and forwards on the FIRST signup call. Every field is
 * optional — an organic/direct signup sends an empty object or omits it. The
 * backend derives {@code AcquisitionChannel} from these and persists them
 * immutably on the user. Never sent on login (attribution is signup-only).
 */
@Schema(description = "Optional marketing attribution captured from the landing URL at signup.")
public record AttributionInfo(
        @Schema(example = "instagram") String utmSource,
        @Schema(example = "paid") String utmMedium,
        @Schema(example = "setembro-lancamento") String utmCampaign,
        @Schema(example = "story-video-a") String utmContent,
        @Schema(example = "economia-mercado") String utmTerm,
        @Schema(description = "Platform click id off the landing URL — fbclid (Meta) or gclid (Google).")
        String clickId,
        @Schema(description = "document.referrer at landing, truncated by the FE.") String referrer,
        @Schema(description = "Path the user first landed on, e.g. /promo.", example = "/") String landingPath
) {
    public boolean isEmpty() {
        return isBlank(utmSource) && isBlank(utmMedium) && isBlank(utmCampaign)
                && isBlank(utmContent) && isBlank(utmTerm) && isBlank(clickId)
                && isBlank(referrer) && isBlank(landingPath);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
