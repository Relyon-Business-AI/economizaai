package com.relyon.economizaai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Entity
@Table(name = "market_chains")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class MarketChain extends BaseEntity {

    @Column(name = "cnpj_root", nullable = false, unique = true, length = 8)
    private String cnpjRoot;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "logo_url", columnDefinition = "TEXT")
    private String logoUrl;
}
