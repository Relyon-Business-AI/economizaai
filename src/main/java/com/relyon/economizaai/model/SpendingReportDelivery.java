package com.relyon.economizaai.model;

import com.relyon.economizaai.model.enums.SpendingReportFrequency;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

import java.time.LocalDate;

/**
 * One spending report handed to a user's channels: the frequency, the first day of the period
 * it covers and the notification it became. The unique (user, frequency, period) constraint
 * makes the insert the send-once claim; the rows stay as the history of reports received.
 */
@Entity
@Table(name = "spending_report_deliveries",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "frequency", "period_start"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class SpendingReportDelivery extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SpendingReportFrequency frequency;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "notification_id")
    private Notification notification;
}
