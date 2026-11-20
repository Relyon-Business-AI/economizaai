package com.relyon.economizaai.service.settings;

/**
 * The shape a text setting must follow when free text is not enough. Returns the text in its
 * canonical form, or refuses it with an {@code InvalidSettingValueException} that tells the
 * admin what is wrong.
 */
@FunctionalInterface
public interface TextFormat {

    String canonical(String settingLabel, String typed);
}
