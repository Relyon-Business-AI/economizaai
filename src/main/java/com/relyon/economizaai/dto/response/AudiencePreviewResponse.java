package com.relyon.economizaai.dto.response;

import java.util.List;

/** Who an audience resolves to RIGHT NOW: total count + a sample of recipient emails. */
public record AudiencePreviewResponse(
        long matchCount,
        List<String> sampleEmails
) {}
