package com.relyon.economizaai.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.Nullable;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.DefaultCorsProcessor;

import java.io.IOException;

/**
 * CORS processor that logs every rejected request. The default processor
 * answers 403 "Invalid CORS request" silently, which makes a misconfigured
 * {@code CORS_ORIGINS} (e.g. a new FE origin missing from the list)
 * indistinguishable from an outage when debugging from logs alone.
 */
@Slf4j
public class LoggingCorsProcessor extends DefaultCorsProcessor {

    @Override
    public boolean processRequest(@Nullable CorsConfiguration configuration, HttpServletRequest request,
                                  HttpServletResponse response) throws IOException {
        var accepted = super.processRequest(configuration, request, response);
        if (!accepted) {
            log.warn("cors.rejected origin={} method={} path={}",
                    request.getHeader(HttpHeaders.ORIGIN), request.getMethod(), request.getRequestURI());
        }
        return accepted;
    }
}
