package com.relyon.economizaai.model;

import com.relyon.economizaai.model.enums.ProductCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

@Entity
@Table(name = "learned_dictionary")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class LearnedDictionaryEntry extends BaseEntity {

    @Column(name = "normalized_token", nullable = false, unique = true, length = 100)
    private String normalizedToken;

    @Column(name = "generic_name", length = 100)
    private String genericName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ProductCategory category;

    @Column(name = "sample_count", nullable = false)
    private Integer sampleCount;

    @Column(name = "promoted_at", nullable = false)
    private LocalDateTime promotedAt;
}
