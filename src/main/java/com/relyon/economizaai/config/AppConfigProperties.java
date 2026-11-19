package com.relyon.economizaai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The force-update lever of the apps ({@code GET /app-config}). The defaults make it a no-op:
 * min-version {@code 0.0.0} never blocks an installed version.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "economizaai.app-config")
public class AppConfigProperties {
    private volatile String minVersion = "0.0.0";
    private volatile String androidUrl = "";
    private volatile String iosUrl = "";
    private volatile String message = "";
}
