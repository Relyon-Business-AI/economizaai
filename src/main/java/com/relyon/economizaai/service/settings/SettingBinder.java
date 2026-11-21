package com.relyon.economizaai.service.settings;

/** Connects a group of settings to the code that uses them. */
public interface SettingBinder {
    void bind(SettingBindings bindings);
}
