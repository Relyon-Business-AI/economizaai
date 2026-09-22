package com.relyon.economizaai.security.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Resolves the originating client IP for rate-limit keying. We sit behind
 * Cloudflare (tunnel/worker), so the immediate {@code remoteAddr} is the
 * proxy's IP — useless for limiting.
 *
 * <p>Header trust matters here: {@code X-Forwarded-For} is client-appendable,
 * so its FIRST entry is attacker-controlled (a spoofed random value per request
 * would defeat IP rate limiting entirely). The same applies to
 * {@code CF-Connecting-IP} when the request did NOT come through Cloudflare —
 * the origin is also reachable directly on its onrender.com hostname, where a
 * client can set that header freely. We therefore only honor
 * {@code CF-Connecting-IP} when the peer that connected to our edge (the LAST
 * {@code X-Forwarded-For} hop, appended by the trusted proxy in front of us,
 * or {@code remoteAddr} without one) is inside Cloudflare's published IP
 * ranges; otherwise the peer IP itself is the client.
 */
public final class ClientIpResolver {

    /** Cloudflare's published edge ranges (https://www.cloudflare.com/ips/). */
    private static final List<Cidr> CLOUDFLARE_RANGES = List.of(
            Cidr.parse("173.245.48.0/20"),
            Cidr.parse("103.21.244.0/22"),
            Cidr.parse("103.22.200.0/22"),
            Cidr.parse("103.31.4.0/22"),
            Cidr.parse("141.101.64.0/18"),
            Cidr.parse("108.162.192.0/18"),
            Cidr.parse("190.93.240.0/20"),
            Cidr.parse("188.114.96.0/20"),
            Cidr.parse("197.234.240.0/22"),
            Cidr.parse("198.41.128.0/17"),
            Cidr.parse("162.158.0.0/15"),
            Cidr.parse("104.16.0.0/13"),
            Cidr.parse("104.24.0.0/14"),
            Cidr.parse("172.64.0.0/13"),
            Cidr.parse("131.0.72.0/22"),
            Cidr.parse("2400:cb00::/32"),
            Cidr.parse("2606:4700::/32"),
            Cidr.parse("2803:f800::/32"),
            Cidr.parse("2405:b500::/32"),
            Cidr.parse("2405:8100::/32"),
            Cidr.parse("2a06:98c0::/29"),
            Cidr.parse("2c0f:f248::/32"));

    private ClientIpResolver() {}

    public static String resolve(HttpServletRequest request) {
        var peerIp = peerIp(request);
        var cfConnectingIp = request.getHeader("CF-Connecting-IP");
        if (cfConnectingIp != null && !cfConnectingIp.isBlank() && isCloudflareEdge(peerIp)) {
            return cfConnectingIp.trim();
        }
        return peerIp != null ? peerIp : "unknown";
    }

    /**
     * The IP that actually connected to our trusted edge: the last
     * X-Forwarded-For hop (appended by the proxy in front of us), else the
     * socket peer.
     */
    private static String peerIp(HttpServletRequest request) {
        var forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            var hops = forwarded.split(",");
            var lastHop = hops[hops.length - 1].trim();
            if (!lastHop.isEmpty()) return lastHop;
        }
        return request.getRemoteAddr();
    }

    private static final Pattern IPV4_LITERAL = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6_LITERAL = Pattern.compile("[0-9a-fA-F:]+");

    private static boolean isCloudflareEdge(String peerIp) {
        var address = parseIpLiteral(peerIp);
        return address != null && CLOUDFLARE_RANGES.stream().anyMatch(range -> range.contains(address));
    }

    /**
     * Parses only IP literals — a plain {@code InetAddress.getByName} would
     * fall back to a DNS lookup for hostname-shaped garbage in the header.
     */
    private static InetAddress parseIpLiteral(String candidate) {
        if (candidate == null) return null;
        var isLiteral = candidate.contains(":")
                ? IPV6_LITERAL.matcher(candidate).matches()
                : IPV4_LITERAL.matcher(candidate).matches();
        if (!isLiteral) return null;
        try {
            return InetAddress.getByName(candidate);
        } catch (UnknownHostException invalidIp) {
            return null;
        }
    }

    private record Cidr(byte[] network, int prefixLength) {

        static Cidr parse(String notation) {
            var parts = notation.split("/");
            try {
                return new Cidr(InetAddress.getByName(parts[0]).getAddress(), Integer.parseInt(parts[1]));
            } catch (UnknownHostException invalidNetwork) {
                throw new IllegalStateException("Invalid CIDR: " + notation, invalidNetwork);
            }
        }

        boolean contains(InetAddress address) {
            var candidate = address.getAddress();
            if (candidate.length != network.length) return false;
            var fullBytes = prefixLength / 8;
            var remainderBits = prefixLength % 8;
            for (var byteIndex = 0; byteIndex < fullBytes; byteIndex++) {
                if (candidate[byteIndex] != network[byteIndex]) return false;
            }
            if (remainderBits == 0) return true;
            var mask = (byte) (0xFF << (8 - remainderBits));
            return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
