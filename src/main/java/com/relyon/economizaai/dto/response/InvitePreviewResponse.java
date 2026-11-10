package com.relyon.economizaai.dto.response;

import java.util.List;

/**
 * What a user sees BEFORE accepting an invite: whose household it is and how many people
 * are already in it. Deliberately minimal — first names only, no e-mails, no ids — the
 * code is a bearer credential and this endpoint answers to anyone who holds it.
 */
public record InvitePreviewResponse(
        String householdName,
        String hostFirstName,
        int memberCount,
        List<String> memberFirstNames
) {}
