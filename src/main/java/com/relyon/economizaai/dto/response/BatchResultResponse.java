package com.relyon.economizaai.dto.response;

/** Result of a bulk receipt op — how many were actually affected (re-queued / deleted). */
public record BatchResultResponse(int affected) {}
