package com.relyon.economizaai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    /**
     * Prototype-scoped so each injection point gets its OWN fresh builder. The
     * builder is mutable and our clients customize it (requestFactory, default
     * headers); a shared singleton builder would leak one client's timeouts/headers
     * (e.g. Expo's Authorization) into the others.
     */
    @Bean
    @Scope("prototype")
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}
