package com.relyon.economizaai.security.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.relyon.economizaai.service.LocalizedMessageService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Production wiring for the rate-limit machinery. Kept as an explicit
 * @Configuration (vs @Component on each class) so @WebMvcTest slices can
 * import {@link com.relyon.economizaai.config.SecurityConfig} without
 * dragging in the filter — see SecurityConfig's ObjectProvider injection.
 */
@Configuration
public class RateLimitConfig {

    @Bean
    public RateLimitRegistry rateLimitRegistry() {
        return new RateLimitRegistry();
    }

    /**
     * Uses a self-owned {@link ObjectMapper} (the response body is a single
     * small record) so the filter doesn't depend on the Spring-wired one —
     * keeps it usable in test contexts that don't load JacksonAutoConfig.
     * JavaTimeModule registered (with WRITE_DATES_AS_TIMESTAMPS disabled) so the
     * 429 ErrorResponse's {@link java.time.LocalDateTime} timestamp serializes as
     * an ISO-8601 string — matching the GlobalExceptionHandler error shape rather
     * than a JSON array.
     */
    @Bean
    public RateLimitFilter rateLimitFilter(RateLimitRegistry registry,
                                           LocalizedMessageService messageService) {
        var mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new RateLimitFilter(registry, messageService, mapper);
    }
}
