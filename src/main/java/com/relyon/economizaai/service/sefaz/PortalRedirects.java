package com.relyon.economizaai.service.sefaz;

import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.regex.Pattern;

/**
 * Redirect handling shared by the adapters that fetch a URL taken from a QR code.
 *
 * <p>The HTTP client must never follow a redirect on its own: the host allow-list is the
 * only thing keeping a fetch on SEFAZ portals, so EVERY hop has to pass through it. The
 * request factory built here has automatic redirects switched off; the adapters follow
 * {@code Location} themselves, re-validating each target, for a bounded number of hops.
 */
final class PortalRedirects {

    /** Redirect hops followed for one fetch before giving up. */
    static final int MAX_HOPS = 4;

    // Manual parsing instead of java.net.URI: real NFC-e QR URLs carry raw '|' in the query.
    private static final Pattern ORIGIN = Pattern.compile("^(https?)://[^/?#]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern ABSOLUTE = Pattern.compile("^https?://.*", Pattern.CASE_INSENSITIVE);

    private PortalRedirects() {}

    static SimpleClientHttpRequestFactory requestFactoryWithoutAutoRedirects(int connectTimeoutMs, int readTimeoutMs) {
        var requestFactory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        requestFactory.setConnectTimeout(connectTimeoutMs);
        requestFactory.setReadTimeout(readTimeoutMs);
        return requestFactory;
    }

    /**
     * Absolute URL a {@code Location} header points to, resolved against the URL that
     * answered with it (absolute, scheme-relative, root-relative or path-relative).
     */
    static String resolveLocation(String currentUrl, String location) {
        var target = location.trim();
        if (ABSOLUTE.matcher(target).matches()) {
            return target;
        }
        var originMatcher = ORIGIN.matcher(currentUrl);
        if (!originMatcher.find()) {
            return target;
        }
        var origin = originMatcher.group();
        if (target.startsWith("//")) {
            return originMatcher.group(1) + ":" + target;
        }
        if (target.startsWith("/")) {
            return origin + target;
        }
        return origin + directoryOf(currentUrl.substring(origin.length())) + target;
    }

    static boolean isHttps(String url) {
        return url.regionMatches(true, 0, "https://", 0, "https://".length());
    }

    /** "/a/b/page?x=1" -> "/a/b/"; an empty path -> "/". */
    private static String directoryOf(String pathAndQuery) {
        var path = pathAndQuery.split("[?#]", 2)[0];
        var lastSlash = path.lastIndexOf('/');
        return lastSlash < 0 ? "/" : path.substring(0, lastSlash + 1);
    }
}
