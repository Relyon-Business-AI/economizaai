package com.relyon.economizaai.security.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientIpResolverTest {

    @Test
    void prefersCfConnectingIpOverEverything() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("CF-Connecting-IP", "203.0.113.10");
        request.addHeader("X-Forwarded-For", "6.6.6.6, 198.51.100.5");

        assertEquals("203.0.113.10", ClientIpResolver.resolve(request));
    }

    @Test
    void usesXForwardedForWhenNoCloudflareHeader() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.10");

        assertEquals("203.0.113.10", ClientIpResolver.resolve(request));
    }

    @Test
    void takesLastHopFromMultiProxyChainNotTheSpoofableFirst() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        // First entry is client-appendable (spoofable); the last hop was added
        // by the trusted proxy directly in front of us.
        request.addHeader("X-Forwarded-For", "6.6.6.6, 198.51.100.5, 203.0.113.10");

        assertEquals("203.0.113.10", ClientIpResolver.resolve(request));
    }

    @Test
    void trimsWhitespaceAroundLastHop() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "198.51.100.5,  203.0.113.10  ");

        assertEquals("203.0.113.10", ClientIpResolver.resolve(request));
    }

    @Test
    void fallsBackToRemoteAddrWithoutHeader() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.50");

        assertEquals("192.168.1.50", ClientIpResolver.resolve(request));
    }

    @Test
    void fallsBackToRemoteAddrWhenHeaderIsBlank() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.50");
        request.addHeader("X-Forwarded-For", "   ");

        assertEquals("192.168.1.50", ClientIpResolver.resolve(request));
    }

    @Test
    void fallsBackToRemoteAddrWhenLastHopIsEmpty() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.50");
        request.addHeader("X-Forwarded-For", "198.51.100.5, ");

        assertEquals("192.168.1.50", ClientIpResolver.resolve(request));
    }

    @Test
    void returnsUnknownWhenNothingResolvable() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(null);

        assertEquals("unknown", ClientIpResolver.resolve(request));
    }
}
