package com.relyon.economizaai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Pin from a user to a market CNPJ they want monitored — bypasses the
 * home-radius filter so e.g. a market on the user's commute still shows
 * up in price intelligence even though it's not "close to home".
 */
@Entity
@Table(name = "user_watched_markets",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "market_cnpj"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class UserWatchedMarket extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "market_cnpj", nullable = false, length = 14)
    private String marketCnpj;
}
