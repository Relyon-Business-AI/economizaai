package com.relyon.economizaai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One month of official food inflation (IBGE IPCA, monthly % change) for one area and one item.
 * The national area has no state; every other area belongs to one UF.
 */
@Entity
@Table(name = "official_inflation_months", uniqueConstraints = @UniqueConstraint(
        name = "uq_official_inflation_area_item_month", columnNames = {"area_code", "item_code", "reference_month"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class OfficialInflationMonth extends BaseEntity {

    @Column(name = "area_code", nullable = false, length = 10)
    private String areaCode;

    @Column(name = "area_name", nullable = false, length = 120)
    private String areaName;

    @Column(length = 2)
    private String state;

    @Column(name = "item_code", nullable = false, length = 10)
    private String itemCode;

    @Column(name = "item_name", nullable = false, length = 160)
    private String itemName;

    /** First day of the month the change refers to. */
    @Column(name = "reference_month", nullable = false)
    private LocalDate referenceMonth;

    @Column(name = "change_percent", nullable = false, precision = 8, scale = 4)
    private BigDecimal changePercent;
}
