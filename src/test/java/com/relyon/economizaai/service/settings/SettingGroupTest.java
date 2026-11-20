package com.relyon.economizaai.service.settings;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class SettingGroupTest {

    @ParameterizedTest
    @EnumSource(SettingGroup.class)
    void aGroupJoinsSeveralSettingsOfOneSectionUnderATitleAndAnExplanation(SettingGroup group) {
        assertThat(group.getMembers()).hasSizeGreaterThan(1).doesNotHaveDuplicates();
        assertThat(group.getMembers()).extracting(RuntimeSetting::getSection).containsOnly(group.getMembers().get(0).getSection());
        assertThat(group.getTitle()).isNotBlank();
        assertThat(group.getExplanation()).hasSizeGreaterThan(40);
        assertThat(group.getMembers()).allSatisfy(member -> assertThat(SettingGroup.of(member)).isEqualTo(group));
    }
}
