package com.relyon.economizaai.config;

import com.relyon.economizaai.security.ratelimit.ClientIpResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Sets requestId in MDC for every HTTP request and clears all MDC keys when
 * the request finishes. This is the anchor for log correlation — userId is
 * added by JwtAuthenticationFilter when present; receiptId / itemId are
 * added by services as the request flows through the pipeline.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MdcContextFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID = "requestId";
    public static final String USER_ID = "userId";
    public static final String RECEIPT_ID = "receiptId";
    public static final String ITEM_ID = "itemId";
    public static final String CLIENT_IP = "clientIp";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        MDC.put(REQUEST_ID, UUID.randomUUID().toString().substring(0, 8));
        // Client IP on every request line — the anchor for tracing an auth
        // failure/429 back to a specific caller (e.g. an App Review attempt).
        MDC.put(CLIENT_IP, ClientIpResolver.resolve(request));
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
