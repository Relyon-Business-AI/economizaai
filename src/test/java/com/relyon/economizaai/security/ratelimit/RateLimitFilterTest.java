package com.relyon.economizaai.security.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.relyon.economizaai.service.LocalizedMessageService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RateLimitFilterTest {

    private RateLimitFilter filter;
    private FilterChain chain;
    private LocalizedMessageService messageService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        messageService = mock(LocalizedMessageService.class);
        when(messageService.translate("rate.limit.exceeded")).thenReturn("Too many requests.");
        var mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper = mapper;
        filter = new RateLimitFilter(new RateLimitRegistry(), messageService, mapper);
        chain = mock(FilterChain.class);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void allowsRequestsBelowAuthLimit() throws Exception {
        for (var i = 0; i < 5; i++) {
            var response = invokeAuthRequest("1.2.3.4");
            assertEquals(200, response.getStatus());
        }
        verify(chain, times(5)).doFilter(any(), any());
    }

    @Test
    void blocksSixthAuthRequestFromSameIp() throws Exception {
        for (var i = 0; i < 5; i++) invokeAuthRequest("5.6.7.8");
        var blocked = invokeAuthRequest("5.6.7.8");
        assertEquals(429, blocked.getStatus());
        assertNotNull(blocked.getHeader("Retry-After"));
    }

    @Test
    void blocked429BodyMatchesErrorResponseShapeWithIsoTimestamp() throws Exception {
        for (var i = 0; i < 5; i++) invokeAuthRequest("7.7.7.7");
        var blocked = invokeAuthRequest("7.7.7.7");

        assertEquals(429, blocked.getStatus());
        assertTrue(blocked.getContentType().startsWith("application/json"),
                "content type must be JSON, was " + blocked.getContentType());
        assertTrue(blocked.getCharacterEncoding().equalsIgnoreCase("UTF-8"),
                "charset must be UTF-8, was " + blocked.getCharacterEncoding());

        var json = objectMapper.readTree(blocked.getContentAsString());
        assertEquals(429, json.get("status").asInt());
        assertEquals("Too many requests.", json.get("message").asText());
        // timestamp must be an ISO-8601 string, not a JSON array like [2026,6,8,...]
        var timestamp = json.get("timestamp");
        assertTrue(timestamp.isTextual(), "timestamp must serialize as an ISO string, was " + timestamp);
        assertDoesNotThrow(() -> LocalDateTime.parse(timestamp.asText()));
    }

    @Test
    void differentIpsHaveSeparateBuckets() throws Exception {
        for (var i = 0; i < 5; i++) invokeAuthRequest("9.9.9.9");
        var fromOtherIp = invokeAuthRequest("8.8.8.8");
        assertEquals(200, fromOtherIp.getStatus(),
                "second IP should not be throttled by the first IP's exhausted bucket");
    }

    @Test
    void doesNotApplyToUnmatchedRoute() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/products");
        request.setRemoteAddr("1.1.1.1");
        var response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, chain);
        assertEquals(200, response.getStatus());
        verify(chain).doFilter(request, response);
    }

    @Test
    void honorsXForwardedForHeader() throws Exception {
        // Simulate Render's proxy: same RemoteAddr (the LB), but two different
        // X-Forwarded-For values should map to two buckets.
        for (var i = 0; i < 5; i++) {
            var req = authRequest("10.0.0.1", "203.0.113.10");
            filter.doFilterInternal(req, new MockHttpServletResponse(), chain);
        }
        var differentClient = authRequest("10.0.0.1", "203.0.113.99");
        var response = new MockHttpServletResponse();
        filter.doFilterInternal(differentClient, response, chain);
        assertEquals(200, response.getStatus());
    }

    @Test
    void allowsThirtyReceiptSubmissionsPerUser() throws Exception {
        authenticateAs("shopper@test.com");
        for (var attempt = 0; attempt < 30; attempt++) {
            var response = invokeSubmitRequest("1.2.3.4");
            assertEquals(200, response.getStatus(), "submission " + (attempt + 1) + " should pass");
        }
    }

    @Test
    void blocksThirtyFirstReceiptSubmissionForSameUser() throws Exception {
        authenticateAs("shopper@test.com");
        for (var attempt = 0; attempt < 30; attempt++) invokeSubmitRequest("1.2.3.4");
        var blocked = invokeSubmitRequest("1.2.3.4");
        assertEquals(429, blocked.getStatus());
        assertNotNull(blocked.getHeader("Retry-After"));
    }

    @Test
    void submitBucketsAreKeyedByUserNotIp() throws Exception {
        authenticateAs("heavy@test.com");
        for (var attempt = 0; attempt < 30; attempt++) invokeSubmitRequest("1.2.3.4");

        // Same IP, different user — must not be throttled by the first user's bucket.
        authenticateAs("light@test.com");
        var otherUser = invokeSubmitRequest("1.2.3.4");
        assertEquals(200, otherUser.getStatus());
    }

    @Test
    void unauthenticatedSubmitFallsBackToIpKey() throws Exception {
        for (var attempt = 0; attempt < 30; attempt++) invokeSubmitRequest("4.4.4.4");
        var blockedSameIp = invokeSubmitRequest("4.4.4.4");
        assertEquals(429, blockedSameIp.getStatus());

        var allowedOtherIp = invokeSubmitRequest("4.4.4.5");
        assertEquals(200, allowedOtherIp.getStatus());
    }

    @Test
    void photoAndChaveOcrEndpointsShareTheSubmitBucket() throws Exception {
        // 30 attempts spread across all three ingestion entry points must
        // drain ONE shared budget, not three separate ones.
        for (var attempt = 0; attempt < 10; attempt++) invokeSubmitRequest("7.7.7.7");
        for (var attempt = 0; attempt < 10; attempt++) invokeSubmitRequest("7.7.7.7", "/api/v1/receipts/photo");
        for (var attempt = 0; attempt < 10; attempt++) invokeSubmitRequest("7.7.7.7", "/api/v1/receipts/chave/photo");

        var blocked = invokeSubmitRequest("7.7.7.7", "/api/v1/receipts/photo");
        assertEquals(429, blocked.getStatus());
    }

    @Test
    void getOnReceiptsRouteIsNotLimited() throws Exception {
        for (var attempt = 0; attempt < 40; attempt++) {
            var request = new MockHttpServletRequest("GET", "/api/v1/receipts");
            request.setRemoteAddr("1.2.3.4");
            var response = new MockHttpServletResponse();
            filter.doFilterInternal(request, response, chain);
            assertEquals(200, response.getStatus());
        }
    }

    @Test
    void successfulMatchedRequestExposesRemainingHeader() throws Exception {
        var response = invokeAuthRequest("6.6.6.6");
        assertEquals("4", response.getHeader("X-RateLimit-Remaining"));
    }

    private void authenticateAs(String email) {
        var authentication = new UsernamePasswordAuthenticationToken(email, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private MockHttpServletResponse invokeSubmitRequest(String ip) throws Exception {
        return invokeSubmitRequest(ip, "/api/v1/receipts");
    }

    private MockHttpServletResponse invokeSubmitRequest(String ip, String path) throws Exception {
        var request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr(ip);
        var response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, chain);
        return response;
    }

    private MockHttpServletResponse invokeAuthRequest(String ip) throws Exception {
        var request = authRequest(ip, null);
        var response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, chain);
        return response;
    }

    private MockHttpServletRequest authRequest(String remoteAddr, String forwardedFor) {
        var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) request.addHeader("X-Forwarded-For", forwardedFor);
        return request;
    }
}
