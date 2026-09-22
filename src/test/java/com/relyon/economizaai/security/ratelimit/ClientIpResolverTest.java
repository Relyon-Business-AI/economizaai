package com.relyon.economizaai.security.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientIpResolverTest {

    private static final String CLOUDFLARE_EDGE_IP = "104.16.1.1";
    private static final String CLOUDFLARE_EDGE_IPV6 = "2606:4700:0:0:0:0:0:1";

    @Test
    void trustsCfConnectingIpWhenPeerIsCloudflareEdge() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("CF-Connecting-IP", "203.0.113.10");
        request.addHeader("X-Forwarded-For", "6.6.6.6, " + CLOUDFLARE_EDGE_IP);

        assertEquals("203.0.113.10", ClientIpResolver.resolve(request));
    }

    @Test
    void trustsCfConnectingIpWhenSocketPeerIsCloudflareEdgeWithoutForwardedHeader() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(CLOUDFLARE_EDGE_IP);
        request.addHeader("CF-Connecting-IP", "203.0.113.10");

        assertEquals("203.0.113.10", ClientIpResolver.resolve(request));
    }

    @Test
    void trustsCfConnectingIpFromCloudflareIpv6Edge() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(CLOUDFLARE_EDGE_IPV6);
        request.addHeader("CF-Connecting-IP", "203.0.113.10");

        assertEquals("203.0.113.10", ClientIpResolver.resolve(request));
    }

    @Test
    void ignoresSpoofedCfConnectingIpWhenPeerIsNotCloudflare() {
        // Direct hit on the onrender.com hostname: Cloudflare is not in the
        // path, so the header is attacker-controlled and must be ignored.
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("CF-Connecting-IP", "1.2.3.4");
        request.addHeader("X-Forwarded-For", "203.0.113.10");

        assertEquals("203.0.113.10", ClientIpResolver.resolve(request));
    }

    @Test
    void ignoresSpoofedCfConnectingIpWhenSocketPeerIsNotCloudflare() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("CF-Connecting-IP", "1.2.3.4");

        assertEquals("203.0.113.10", ClientIpResolver.resolve(request));
    }

    @Test
    void ignoresCfConnectingIpWhenPeerIsUnparsable() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("CF-Connecting-IP", "1.2.3.4");
        request.addHeader("X-Forwarded-For", "not-an-ip");

        assertEquals("not-an-ip", ClientIpResolver.resolve(request));
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
