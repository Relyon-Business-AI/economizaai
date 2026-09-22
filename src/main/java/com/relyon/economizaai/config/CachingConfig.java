package com.relyon.economizaai.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

import java.time.Duration;

/**
 * Read-path performance for the two heaviest home-screen calls.
 *
 * <ul>
 *   <li><b>Server cache</b> — Caffeine, per-cache TTL. {@code dashboard} (2 min)
 *       and {@code insightsSpend} (5 min). Keys fold in a per-household
 *       generation counter ({@link com.relyon.economizaai.service.cache.HouseholdCacheGen})
 *       so a single bump on any receipt mutation invalidates that household's
 *       entries without touching anyone else's.</li>
 *   <li><b>ETags</b> — {@link ShallowEtagHeaderFilter} on the dashboard + insights
 *       paths. The app sends {@code If-None-Match}; unchanged responses come back
 *       {@code 304 Not Modified} with no body (saves bandwidth + client parsing).</li>
 * </ul>
 *
 * <p>NOTE: the cache is in-process. Correct for a single instance; a multi-instance
 * deployment would need a shared store (Redis) — see DEV_NOTES.
 */
@Configuration
@EnableCaching
public class CachingConfig {

    public static final String DASHBOARD_CACHE = "dashboard";
    public static final String INSIGHTS_SPEND_CACHE = "insightsSpend";

    @Bean
    public CacheManager cacheManager() {
        var manager = new CaffeineCacheManager();
        manager.registerCustomCache(DASHBOARD_CACHE, Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(2))
                .maximumSize(10_000)
                .build());
        manager.registerCustomCache(INSIGHTS_SPEND_CACHE, Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(5))
                .maximumSize(10_000)
                .build());
        return manager;
    }

    @Bean
    public FilterRegistrationBean<ShallowEtagHeaderFilter> etagFilter() {
        var registration = new FilterRegistrationBean<>(new ShallowEtagHeaderFilter());
        registration.addUrlPatterns("/api/v1/dashboard", "/api/v1/insights/*");
        registration.setName("etagFilter");
        return registration;
    }
}
