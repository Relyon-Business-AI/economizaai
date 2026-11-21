package com.relyon.economizaai.exception;

public class SettingNotFoundException extends DomainException {
    public SettingNotFoundException(String settingKey) {
        super("admin.settings.not-found", settingKey);
    }
}
