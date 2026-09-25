package com.relyon.economizaai.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Rejects oversized NON-multipart request bodies by Content-Length BEFORE the body is buffered
 * into heap. Bean validation ({@code @Size} on the prefetched/device-content {@code rawContent})
 * only fires AFTER Tomcat has read the whole body, so a huge JSON payload would pressure memory
 * before the 400 — this returns 413 up front instead. Multipart uploads are excluded: they're
 * streamed and already bounded by {@code spring.servlet.multipart.*}. Requests without a
 * Content-Length (chunked) fall through and are still caught by {@code @Size} downstream.
 */
@Slf4j
@Component
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final long maxJsonBodyBytes;

    public RequestSizeLimitFilter(
            @Value("${economizaai.security.max-json-body-bytes:5242880}") long maxJsonBodyBytes) {
        this.maxJsonBodyBytes = maxJsonBodyBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        var contentType = request.getContentType();
        var multipart = contentType != null && contentType.toLowerCase().startsWith("multipart/");
        var contentLength = request.getContentLengthLong();
        if (!multipart && contentLength > maxJsonBodyBytes) {
            log.warn("request.rejected reason=body_too_large bytes={} limit={} path={}",
                    contentLength, maxJsonBodyBytes, request.getRequestURI());
            response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
            return;
        }
        filterChain.doFilter(request, response);
    }
}
