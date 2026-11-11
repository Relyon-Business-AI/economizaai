package com.relyon.economizaai.model;

import com.relyon.economizaai.model.enums.InfosimplesOutcome;
import com.relyon.economizaai.model.enums.ReceiptChannel;
import com.relyon.economizaai.model.enums.ReceiptEntryMethod;
import com.relyon.economizaai.model.enums.ReceiptOrigin;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "receipts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Receipt extends BaseEntity implements HouseholdScoped {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id", nullable = false)
    private Household household;

    // The household this receipt ORIGINALLY belonged to. Set once at scan time,
    // never rewritten on merge (household_id is the current location). Lets a split
    // restore each person's data to where it came from. See HouseholdMergeService.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "origin_household_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private Household originHousehold;

    @Column(name = "chave_acesso", nullable = false, length = 44)
    private String chaveAcesso;

    /** SCAN (SEFAZ-fetched, default) or PHOTO (vision-extracted — personal history only, never feeds the index). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private ReceiptOrigin origin = ReceiptOrigin.SCAN;

    /** HOW the user entered it (QR scan vs typed/photographed chave vs photo vs import) — analytics
     * only, a finer cut than {@link ReceiptOrigin}. See {@link ReceiptEntryMethod}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "entry_method", length = 20)
    private ReceiptEntryMethod entryMethod;

    /** IN_STORE (default) or ONLINE — from the nota's indPres. Orthogonal to origin/segment. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private ReceiptChannel channel = ReceiptChannel.IN_STORE;

    @Enumerated(EnumType.STRING)
    @Column(length = 2)  // null for PHOTO-origin receipts (no SEFAZ document exists)
    private UnidadeFederativa uf;

    @Column(name = "cnpj_emitente", length = 14)
    private String cnpjEmitente;

    @Column(name = "market_name")
    private String marketName;

    @Column(name = "market_address", length = 500)
    private String marketAddress;

    @Column(name = "issued_at")
    private LocalDateTime issuedAt;

    @Column(name = "total_amount", precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "discount_total", precision = 12, scale = 2)
    private BigDecimal discountTotal;

    @Column(name = "approx_tax_federal", precision = 12, scale = 2)
    private BigDecimal approxTaxFederal;

    @Column(name = "approx_tax_estadual", precision = 12, scale = 2)
    private BigDecimal approxTaxEstadual;

    @Column(name = "qr_payload", nullable = false, columnDefinition = "TEXT")
    private String qrPayload;

    @Column(name = "source_url", columnDefinition = "TEXT")
    private String sourceUrl;

    // The portal document this receipt was parsed from is NOT mapped here: it lives in
    // ReceiptRawHtml (own table, read via ReceiptRawHtmlService) so no receipt query carries it.

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReceiptStatus status;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "parse_error_reason", columnDefinition = "TEXT")
    private String parseErrorReason;

    // True while a contingency note is in the auto-resync loop (ContingencyResyncSweeper set it on
    // re-queue). The ingest success path reads it to notify the owner their stuck note was recovered;
    // it's cleared on recovery (success) and on give-up (72h window elapsed).
    @Column(name = "resyncing_from_contingency", nullable = false)
    private boolean resyncingFromContingency;

    // Set by ContingencyResyncSweeper on re-queue and consumed (cleared) by ImportReconsultWorker at
    // dispatch: the NEXT queued attempt must use free paths only (no captcha, Infosimples or LLM).
    @Column(name = "resync_free_only", nullable = false)
    private boolean resyncFreeOnly;

    // True once the contingency resync granted this nota its single paid attempt (the last one
    // before the window closes) — every other resync attempt is free-only.
    @Column(name = "contingency_paid_attempted", nullable = false)
    private boolean contingencyPaidAttempted;

    // True once ProcessingReceiptSweeper gave this stranded scan its single second chance
    // (re-queued instead of failed) — the guard that keeps the re-queue from looping.
    @Column(name = "stranded_requeued", nullable = false)
    private boolean strandedRequeued;

    // How the paid Infosimples lookup answered in the server attempt that handed this nota to the
    // device (NEEDS_DEVICE_FETCH); null = not tried. Read by the device-fetch steps so the same
    // paid lookup is not repeated — never after a "no data" answer.
    @Enumerated(EnumType.STRING)
    @Column(name = "infosimples_outcome", length = 20)
    private InfosimplesOutcome infosimplesOutcome;

    // False ONLY for device-fetched (client-authored) content from a state that isn't on the
    // device-fetch trust allowlist — such content is personal-history only and never feeds the
    // collaborative index (same trust class as PHOTO). Default/true for every server-fetched and
    // trusted device-fetch receipt. Read by PriceIndexService#isContributionBlocked at confirm.
    @Column(name = "index_eligible", nullable = false)
    @Builder.Default
    private boolean indexEligible = true;

    // True for the COPY a household split leaves behind (HouseholdSplitService). A copy is not a
    // submission, so it does not count toward the FREE monthly receipt cap.
    @Column(name = "split_copy", nullable = false)
    @Builder.Default
    private boolean splitCopy = false;

    // BatchSize: list endpoints (GET /receipts, dashboard) read items per row —
    // batching turns that page-sized N+1 into one IN query.
    @OneToMany(mappedBy = "receipt", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("lineNumber ASC")
    @BatchSize(size = 50)
    @Builder.Default
    private List<ReceiptItem> items = new ArrayList<>();

    public void addItem(ReceiptItem item) {
        items.add(item);
        item.setReceipt(this);
    }

    // Default origin to the current household on first persist, so callers that
    // build a Receipt never have to remember to set it. Merge code overrides
    // household_id later but leaves originHousehold untouched.
    @PrePersist
    private void defaultOriginHousehold() {
        if (originHousehold == null) {
            originHousehold = household;
        }
    }

    @Override
    public String collisionKey() {
        return chaveAcesso;
    }
}
