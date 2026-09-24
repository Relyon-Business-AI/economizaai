package com.relyon.economizaai.config;

import com.relyon.economizaai.security.JwtAuthenticationFilter;
import com.relyon.economizaai.security.ratelimit.RateLimitFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

@Slf4j
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    /**
     * Optional so {@code @WebMvcTest} slices that import only this config
     * don't have to wire the rate-limit machinery. In production the bean
     * is always present and gets added to the chain.
     */
    private final ObjectProvider<RateLimitFilter> rateLimitFilterProvider;

    @Value("${economizaai.cors.allowed-origins:http://localhost:3000,http://localhost:5173,http://localhost:8081}")
    private String allowedOrigins;

    @Value("${economizaai.metrics.username:metrics}")
    private String metricsUsername;

    /** Blank by default → the metrics endpoint is fail-closed (nobody can authenticate). */
    @Value("${economizaai.metrics.password:}")
    private String metricsPassword;

    /**
     * Dedicated chain for the Prometheus scrape endpoint: HTTP Basic auth so the
     * scraper can reach it but the public internet can't. Ordered before the main
     * (JWT) chain because its matcher is narrower. Fail-closed: when no password is
     * configured no user exists, so every request gets 401 — metrics are never
     * exposed unauthenticated. {@code /actuator/health} stays public on the main chain.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain metricsSecurityFilterChain(HttpSecurity http) throws Exception {
        var users = new InMemoryUserDetailsManager();
        if (metricsPassword != null && !metricsPassword.isBlank()) {
            users.createUser(User.withUsername(metricsUsername)
                    .password(passwordEncoder().encode(metricsPassword))
                    .roles("METRICS")
                    .build());
        }
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(passwordEncoder());

        http
                .securityMatcher("/actuator/prometheus")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("METRICS"))
                .authenticationProvider(provider)
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Picks up the corsFilter bean below (logs rejected origins).
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/**", "/api/v1/legal/**", "/api/v1/webhooks/**", "/api/v1/contact", "/api/v1/beta-signup", "/api/v1/visits", "/swagger-ui/**", "/v3/api-docs/**", "/actuator/health").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        // Model-training / catalog-mutating categorizer endpoints are ADMIN-only.
                        // The read/debug ones (classify, ml/predict, status, quality) stay open to
                        // authenticated users. benchmark is a POST here because it records a snapshot.
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/categorizer/retrain",
                                "/api/v1/categorizer/auto-promote",
                                "/api/v1/categorizer/promote-consensus",
                                "/api/v1/categorizer/ean-catalog/import",
                                "/api/v1/categorizer/ean-catalog/import-off",
                                "/api/v1/categorizer/dictionary/import",
                                "/api/v1/categorizer/dictionary/curated/import",
                                "/api/v1/categorizer/brands/import",
                                "/api/v1/categorizer/brands/derive-from-catalog",
                                "/api/v1/categorizer/brands/promote-aliases",
                                "/api/v1/categorizer/benchmark",
                                "/api/v1/categorizer/benchmark/import").hasRole("ADMIN")
                        // Dictionary MANAGEMENT (list + single delete) is ADMIN-only — it exposes and
                        // edits the full curated/learned dictionary, unlike the read/debug GETs above.
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/categorizer/dictionary/curated",
                                "/api/v1/categorizer/dictionary/learned").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE,
                                "/api/v1/categorizer/learned",
                                "/api/v1/categorizer/consensus",
                                "/api/v1/categorizer/dictionary/curated/*",
                                "/api/v1/categorizer/dictionary/learned/*").hasRole("ADMIN")
                        // Canonical products are GLOBAL — one tester's edit would
                        // change the product for every household. Create also
                        // relinks other households' receipt items by EAN, so it's
                        // ADMIN-only too. POST /products/*/aliases stays open: it's
                        // the app's unmatched-item mapping flow, and the alias
                        // conflict check stops remapping established descriptions.
                        .requestMatchers(HttpMethod.POST, "/api/v1/products").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/products/*").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        // Unauthenticated → 401 (anonymous request, no token, expired token).
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                        // Authenticated but missing the required role → 403.
                        // setStatus + write a JSON body, instead of sendError() which can
                        // forward to /error and re-trigger the security chain.
                        .accessDeniedHandler((request, response, ex2) -> {
                            response.setStatus(HttpStatus.FORBIDDEN.value());
                            response.setContentType("application/json");
                            response.getWriter().write("{\"status\":403,\"message\":\"Forbidden\"}");
                        })
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        // Rate limiting runs AFTER auth so per-user buckets can read the
        // principal. /auth/* rules fall through to IP-based keying. Skipped
        // when the bean isn't present (test slices that import only this config).
        rateLimitFilterProvider.ifAvailable(filter ->
                http.addFilterAfter(filter, JwtAuthenticationFilter.class));
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    /** Picked up by {@code .cors()} in the main chain; rejections get logged. */
    @Bean
    public CorsFilter corsFilter() {
        var filter = new CorsFilter(corsConfigurationSource());
        filter.setCorsProcessor(new LoggingCorsProcessor());
        return filter;
    }

    /** Keep the CorsFilter inside the security chain only — no container-level auto-registration. */
    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilterRegistration(CorsFilter corsFilter) {
        var registration = new FilterRegistrationBean<>(corsFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        log.info("cors.configured allowed-origins={}", allowedOrigins);
        var configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(allowedOrigins.split(",")));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
