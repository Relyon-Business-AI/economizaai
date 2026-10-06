package com.relyon.economizaai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Grants a MERCHANT user access to a chain (cnpj_root = first 8 CNPJ digits),
 * covering every store of that chain. Admin-managed in the MVP — see
 * docs/MERCHANT_ACCOUNTS.md.
 */
@Entity
@Table(name = "merchant_access")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class MerchantAccess extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "cnpj_root", nullable = false, length = 8)
    private String cnpjRoot;
}
