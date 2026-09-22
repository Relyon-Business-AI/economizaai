package com.relyon.economizaai.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Default config = no {@code economizaai.metrics.password} → the Prometheus
 * endpoint is fail-closed: no credentials authenticate, so it's never exposed.
 * {@code /actuator/health} stays public regardless.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@AutoConfigureMockMvc
class MetricsEndpointFailClosedTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void prometheus_withoutCredentials_isUnauthorized() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void prometheus_withAnyCredentials_isUnauthorizedWhenNoPasswordConfigured() throws Exception {
        mockMvc.perform(get("/actuator/prometheus").with(httpBasic("metrics", "anything")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void health_staysPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }
}
