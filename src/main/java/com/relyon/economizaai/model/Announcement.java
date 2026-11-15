package com.relyon.economizaai.model;

import com.relyon.economizaai.model.enums.AnnouncementDestination;
import com.relyon.economizaai.model.enums.AnnouncementKind;
import com.relyon.economizaai.model.enums.AnnouncementStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * One in-app announcement (a new feature, a tip or a notice) shown as a card on the Home screen
 * while it is active and inside its {@code [startsAt, endsAt)} window, until the user dismisses
 * it. {@code startsAt}/{@code endsAt} are Brasília wall-clock; a null {@code endsAt} never ends.
 */
@Entity
@Table(name = "announcements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Announcement extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AnnouncementKind kind;

    @Column(nullable = false, length = 80)
    private String title;

    @Column(length = 160)
    private String body;

    @Column(name = "action_label", length = 30)
    private String actionLabel;

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private AnnouncementDestination destination;

    @Column(name = "starts_at", nullable = false)
    private LocalDateTime startsAt;

    /** Exclusive end of the window; null = runs until switched off. */
    @Column(name = "ends_at")
    private LocalDateTime endsAt;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    public AnnouncementStatus statusAt(LocalDateTime brasiliaNow) {
        if (!active) return AnnouncementStatus.INACTIVE;
        if (endsAt != null && !endsAt.isAfter(brasiliaNow)) return AnnouncementStatus.ENDED;
        if (startsAt.isAfter(brasiliaNow)) return AnnouncementStatus.SCHEDULED;
        return AnnouncementStatus.LIVE;
    }
}
