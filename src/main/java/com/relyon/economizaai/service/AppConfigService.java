package com.relyon.economizaai.service;

import com.relyon.economizaai.config.AppConfigProperties;
import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.dto.response.AppConfigResponse;
import com.relyon.economizaai.service.auth.EmailChangeService;
import com.relyon.economizaai.service.auth.EmailVerificationService;
import com.relyon.economizaai.service.auth.PasswordResetService;
import com.relyon.economizaai.service.auth.PhoneVerificationService;
import com.relyon.economizaai.service.settings.RuntimeSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serves the remote config of the apps: the force-update lever plus the business settings the
 * apps show or apply. Both are changed in the admin settings, with no deploy. Along with them
 * go a few facts that are fixed in code (security and privacy) but that the apps state in their
 * texts, so no screen retypes the number.
 */
@Service
@RequiredArgsConstructor
public class AppConfigService {

    private final AppConfigProperties properties;
    private final RuntimeSettings runtimeSettings;
    private final CollaborativeProperties collaborativeProperties;

    public AppConfigResponse current() {
        return new AppConfigResponse(properties.getMinVersion(),
                blankToNull(properties.getAndroidUrl()), blankToNull(properties.getIosUrl()),
                blankToNull(properties.getMessage()), settingsAndFixedFacts());
    }

    private Map<String, Object> settingsAndFixedFacts() {
        var published = new LinkedHashMap<String, Object>(runtimeSettings.publishedToApps());
        published.putAll(fixedFacts());
        return published;
    }

    /** Not editable in the admin: each one is the very value the rule it describes applies. */
    Map<String, Object> fixedFacts() {
        var facts = new LinkedHashMap<String, Object>();
        facts.put("emailVerificationCodeHours", EmailVerificationService.CODE_TTL_HOURS);
        facts.put("passwordResetCodeMinutes", PasswordResetService.CODE_TTL_MINUTES);
        facts.put("emailChangeCodeMinutes", EmailChangeService.CODE_TTL_MINUTES);
        facts.put("phoneCodeMinutes", PhoneVerificationService.OTP_TTL_MINUTES);
        facts.put("sensitiveMinHouseholds", collaborativeProperties.getCollaborative().getMinHouseholdsForPublic());
        return facts;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
