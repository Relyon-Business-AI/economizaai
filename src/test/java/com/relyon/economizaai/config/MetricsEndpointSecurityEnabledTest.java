package com.relyon.economizaai.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * With {@code economizaai.metrics.password} set, the Prometheus endpoint is
 * reachable with the right HTTP Basic credentials (the scraper's path) and
 * rejects wrong/missing ones.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "economizaai.metrics.username=scraper",
        "economizaai.metrics.password=s3cret-metrics"
})
class MetricsEndpointSecurityEnabledTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void prometheus_withValidCredentials_isOk() throws Exception {
        mockMvc.perform(get("/actuator/prometheus").with(httpBasic("scraper", "s3cret-metrics")))
                .andExpect(status().isOk());
    }

    @Test
    void prometheus_withWrongPassword_isUnauthorized() throws Exception {
        mockMvc.perform(get("/actuator/prometheus").with(httpBasic("scraper", "wrong")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void prometheus_withoutCredentials_isUnauthorized() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());
    }
}
