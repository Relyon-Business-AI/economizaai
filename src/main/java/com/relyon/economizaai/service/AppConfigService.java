package com.relyon.economizaai.service;

import com.relyon.economizaai.dto.response.AppConfigResponse;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Serves the remote config for the mobile force-update gate. Values come from
 * {@code economizaai.app-config.*} (env vars on Render); the defaults make the
 * gate a NO-OP — min-version {@code 0.0.0} never blocks any installed version —
 * so prod works with no new env vars until the owner pulls the lever.
 */
@Slf4j
@Service
public class AppConfigService {

    @Value("${economizaai.app-config.min-version:0.0.0}")
    private String minVersion;

    @Value("${economizaai.app-config.android-url:}")
    private String androidUrl;

    @Value("${economizaai.app-config.ios-url:}")
    private String iosUrl;

    @Value("${economizaai.app-config.message:}")
    private String message;

    @PostConstruct
    void logEffectiveConfig() {
        log.info("app_config.loaded min_version={} android_url={} ios_url={} message_set={}",
                minVersion, blankToNull(androidUrl), blankToNull(iosUrl), blankToNull(message) != null);
    }

    public AppConfigResponse current() {
        return new AppConfigResponse(minVersion,
                blankToNull(androidUrl), blankToNull(iosUrl), blankToNull(message));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
