package com.relyon.economizaai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The portal document (HTML, or the uploaded XML) a receipt was parsed from, kept as evidence
 * for reparse/retry. Lives in its own table, keyed by the receipt id, so loading a
 * {@link Receipt} never carries the document along — it is read only where it is needed.
 * Deliberately not referenced from {@link Receipt}; the row is removed by the database when
 * the receipt is deleted.
 */
@Entity
@Table(name = "receipt_raw_html")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReceiptRawHtml {

    @Id
    @Column(name = "receipt_id")
    private UUID receiptId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "receipt_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Receipt receipt;

    @Column(name = "raw_html", nullable = false, columnDefinition = "TEXT")
    private String rawHtml;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    private void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    private void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
