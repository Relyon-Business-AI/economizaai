package com.relyon.economizaai.model;

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
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * A user closed an announcement's card. The unique (user, announcement) pair makes a repeated
 * or concurrent dismissal a no-op; the rows go away with the user or the announcement.
 */
@Entity
@Table(name = "announcement_dismissals",
       uniqueConstraints = @UniqueConstraint(name = "uq_announcement_dismissals_user_announcement",
               columnNames = {"user_id", "announcement_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class AnnouncementDismissal extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "announcement_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Announcement announcement;
}
