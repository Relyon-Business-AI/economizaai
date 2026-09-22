package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.DataShareConsent;
import com.relyon.economizaai.model.enums.ConsentStatus;
import com.relyon.economizaai.model.enums.LeaveScope;

import java.time.LocalDateTime;
import java.util.UUID;

/** A data-share consent request, as the grantor or requester sees it. */
public record ConsentResponse(
        UUID id,
        UUID requesterId,
        String requesterName,
        UUID grantorId,
        String grantorName,
        LeaveScope scope,
        ConsentStatus status,
        LocalDateTime expiresAt
) {
    public static ConsentResponse from(DataShareConsent c) {
        return new ConsentResponse(
                c.getId(),
                c.getRequester().getId(), c.getRequester().getName(),
                c.getGrantor().getId(), c.getGrantor().getName(),
                c.getScope(), c.getStatus(), c.getExpiresAt());
    }
}
