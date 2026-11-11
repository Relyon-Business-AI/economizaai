package com.relyon.economizaai.service.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailNormalizerTest {

    @Test
    void trimsAndLowerCases() {
        assertThat(EmailNormalizer.normalize("  Maria.Silva@Example.COM ")).isEqualTo("maria.silva@example.com");
    }

    @Test
    void nullStaysNull() {
        assertThat(EmailNormalizer.normalize(null)).isNull();
    }
}
