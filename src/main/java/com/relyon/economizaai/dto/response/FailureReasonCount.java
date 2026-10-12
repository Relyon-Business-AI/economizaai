package com.relyon.economizaai.dto.response;

/**
 * One slice of the admin "falhas por motivo" pie: the raw {@code parseErrorReason}
 * ("key:args", e.g. {@code receipt.paid_api.quota_exceeded:INFOSIMPLES}) and how
 * many FAILED_PARSE notas carry it under the current filters. The FE maps the raw
 * reason to a friendly label/colour.
 */
public record FailureReasonCount(String reason, long count) {
}
