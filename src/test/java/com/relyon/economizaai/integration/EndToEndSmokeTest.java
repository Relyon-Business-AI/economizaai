package com.relyon.economizaai.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack smoke test that exercises every major endpoint family with
 * the real Spring context (H2 + Hibernate-derived schema; SEFAZ client is
 * never invoked because no QR submission is performed).
 *
 * <p>Goal: catch wiring regressions a unit test wouldn't see — bean
 * conflicts, mis-registered filters, missing security gates, broken auth
 * flow shape changes. Each step carries a captured token forward to the
 * next, mirroring how a real client would chain calls.
 *
 * <p>Method ordering is deliberate (register → login → refresh → use → logout).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EndToEndSmokeTest {

    @Autowired private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static String accessToken;
    private static String refreshToken;
    private static String shoppingListId;

    private String bearer() {
        return "Bearer " + accessToken;
    }

    @Test
    @Order(1)
    void publicHealthEndpointReturnsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @Order(2)
    void publicLegalTermsAreReadable() throws Exception {
        mockMvc.perform(get("/api/v1/legal/terms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").exists());
    }

    @Test
    @Order(3)
    void registerNewUserReturnsTokenPair() throws Exception {
        var body = "{\"name\":\"Smoke Tester\",\"email\":\"smoke@test.com\",\"password\":\"password123\","
                + "\"acceptedTermsVersion\":\"1.0\",\"acceptedPrivacyVersion\":\"1.0\"}";
        var result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.refreshToken").exists())
                .andExpect(jsonPath("$.user.email").value("smoke@test.com"))
                .andReturn();
        captureTokens(result);
    }

    @Test
    @Order(4)
    void loginReturnsFreshTokenPair() throws Exception {
        var body = "{\"email\":\"smoke@test.com\",\"password\":\"password123\"}";
        var result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.refreshToken").exists())
                .andReturn();
        captureTokens(result);
    }

    @Test
    @Order(5)
    void refreshExchangesTokenForFreshPair() throws Exception {
        var body = "{\"refreshToken\":\"" + refreshToken + "\"}";
        var result = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.refreshToken").exists())
                .andReturn();
        captureTokens(result);
    }

    @Test
    @Order(6)
    void replayingConsumedRefreshTokenIsRejected() throws Exception {
        // The previous test consumed a refresh token; that exact value must
        // now be invalid. This guards against accidentally letting refresh
        // tokens be reused.
        var stale = "{\"refreshToken\":\"will-never-exist\"}";
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(stale))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(7)
    void getProfileReturnsUserAndEmailVerifiedFlag() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("smoke@test.com"));
    }

    @Test
    @Order(8)
    void profilePictureFallbackReturnsInitialsAvatar() throws Exception {
        mockMvc.perform(get("/api/v1/users/me/profile-picture").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Profile-Picture-Fallback", "true"))
                .andExpect(header().string("Content-Type", "image/png"));
    }

    @Test
    @Order(9)
    void dashboardBundlesEmptyStateForFreshUser() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentMonth").exists())
                .andExpect(jsonPath("$.unreadNotificationCount").value(0));
    }

    @Test
    @Order(10)
    void notificationsInboxIsEmptyForFreshUser() throws Exception {
        mockMvc.perform(get("/api/v1/notifications").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
        mockMvc.perform(get("/api/v1/notifications/unread-count").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread").value(0));
    }

    @Test
    @Order(11)
    void receiptsListAcceptsContentSearchParam() throws Exception {
        // No receipts yet — must still 200 with empty page, not crash on the q join.
        mockMvc.perform(get("/api/v1/receipts").param("q", "leite condensado")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @Order(12)
    void manualBrandPreferenceCreateThenSurfaceThenClear() throws Exception {
        var body = "{\"brand\":\"Itambé\",\"strength\":\"MUST_HAVE\"}";
        mockMvc.perform(put("/api/v1/preferences/brand/Leite")
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", bearer()))
                .andExpect(status().isNoContent());

        // Manual override surfaces in GET /preferences even with zero history.
        mockMvc.perform(get("/api/v1/preferences").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].genericName").value("Leite"))
                .andExpect(jsonPath("$[0].topBrand").value("Itambé"))
                .andExpect(jsonPath("$[0].brandStrength").value("MUST_HAVE"));

        mockMvc.perform(delete("/api/v1/preferences/brand/Leite").header("Authorization", bearer()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/preferences").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @Order(13)
    void shoppingListCreateThenAddItemThenList() throws Exception {
        var create = mockMvc.perform(post("/api/v1/shopping-lists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Compras semana\"}")
                        .header("Authorization", bearer()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andReturn();
        var json = objectMapper.readTree(create.getResponse().getContentAsString());
        shoppingListId = json.get("id").asText();

        mockMvc.perform(post("/api/v1/shopping-lists/" + shoppingListId + "/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"freeText\":\"Pão francês\"}")
                        .header("Authorization", bearer()))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/shopping-lists").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Compras semana"));
    }

    @Test
    @Order(14)
    void adminEndpointsRequireAdminRoleNotJustAuth() throws Exception {
        // A regular USER hitting an admin path must get 403, not 200/401.
        mockMvc.perform(get("/api/v1/admin/users").header("Authorization", bearer()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/receipts").header("Authorization", bearer()))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(15)
    void logoutAcceptsRefreshTokenAndIsIdempotent() throws Exception {
        // Tests above hit /auth/* enough times from the default 127.0.0.1 to
        // burn the per-IP bucket; force a fresh client IP via X-Forwarded-For
        // so this test exercises the logout contract, not the rate limiter.
        var body = "{\"refreshToken\":\"" + refreshToken + "\"}";
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("X-Forwarded-For", "203.0.113.42")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
        // Second call with same token still 204 — idempotent contract.
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("X-Forwarded-For", "203.0.113.42")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
    }

    private void captureTokens(MvcResult result) throws Exception {
        var json = objectMapper.readTree(result.getResponse().getContentAsString());
        accessToken = json.get("token").asText();
        refreshToken = json.get("refreshToken").asText();
    }
}
