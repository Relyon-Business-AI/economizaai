package com.relyon.economizaai.security.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the originating client IP for rate-limit keying. We sit behind
 * Cloudflare (tunnel/worker), so the immediate {@code remoteAddr} is the
 * proxy's IP — useless for limiting.
 *
 * <p>Header trust matters here: {@code X-Forwarded-For} is client-appendable,
 * so its FIRST entry is attacker-controlled (a spoofed random value per request
 * would defeat IP rate limiting entirely). We therefore prefer
 * {@code CF-Connecting-IP}, which Cloudflare overwrites with the IP it
 * actually saw, and otherwise take the LAST {@code X-Forwarded-For} hop — the
 * one appended by the trusted proxy in front of us, not by the client.
 */
public final class ClientIpResolver {

    private ClientIpResolver() {}

    public static String resolve(HttpServletRequest request) {
        var cfConnectingIp = request.getHeader("CF-Connecting-IP");
        if (cfConnectingIp != null && !cfConnectingIp.isBlank()) {
            return cfConnectingIp.trim();
        }
        var forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            var hops = forwarded.split(",");
            var lastHop = hops[hops.length - 1].trim();
            if (!lastHop.isEmpty()) return lastHop;
        }
        var addr = request.getRemoteAddr();
        return addr != null ? addr : "unknown";
    }
}
