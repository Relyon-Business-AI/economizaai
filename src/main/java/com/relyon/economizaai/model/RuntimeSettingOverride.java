package com.relyon.economizaai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * A business setting changed in the admin. The row holds the value in the setting's canonical
 * text form; no row means the value configured for the environment is in effect.
 */
@Entity
@Table(name = "runtime_setting_overrides")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class RuntimeSettingOverride extends BaseEntity {

    @Column(name = "setting_key", nullable = false, length = 120, unique = true)
    private String settingKey;

    @Column(name = "setting_value", nullable = false, columnDefinition = "TEXT")
    private String value;

    @Column(name = "updated_by", length = 255)
    private String updatedBy;
}
