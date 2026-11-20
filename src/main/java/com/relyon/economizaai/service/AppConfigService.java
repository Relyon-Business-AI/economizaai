package com.relyon.economizaai.service;

import com.relyon.economizaai.config.AppConfigProperties;
import com.relyon.economizaai.dto.response.AppConfigResponse;
import com.relyon.economizaai.service.settings.RuntimeSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Serves the remote config of the apps: the force-update lever plus the business settings the
 * apps show or apply. Both are changed in the admin settings, with no deploy.
 */
@Service
@RequiredArgsConstructor
public class AppConfigService {

    private final AppConfigProperties properties;
    private final RuntimeSettings runtimeSettings;

    public AppConfigResponse current() {
        return new AppConfigResponse(properties.getMinVersion(),
                blankToNull(properties.getAndroidUrl()), blankToNull(properties.getIosUrl()),
                blankToNull(properties.getMessage()), runtimeSettings.publishedToApps());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
