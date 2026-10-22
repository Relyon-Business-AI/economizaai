package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.AiFinding;
import com.relyon.economizaai.model.enums.AiFindingStatus;
import com.relyon.economizaai.model.enums.AiFindingType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface AiFindingRepository extends JpaRepository<AiFinding, UUID> {

    Page<AiFinding> findByStatusOrderByCreatedAtDesc(AiFindingStatus status, Pageable pageable);

    /** model, total, approved, rejected, avgConfidence — qualidade por modelo (taxa de aceitação). */
    @org.springframework.data.jpa.repository.Query("""
        SELECT f.model, count(f),
               coalesce(sum(case when f.status = com.relyon.economizaai.model.enums.AiFindingStatus.APPROVED then 1 else 0 end),0),
               coalesce(sum(case when f.status = com.relyon.economizaai.model.enums.AiFindingStatus.REJECTED then 1 else 0 end),0),
               coalesce(avg(f.confidence),0)
        FROM AiFinding f
        WHERE f.model IS NOT NULL AND f.createdAt >= :since
        GROUP BY f.model
    """)
    List<Object[]> statsByModel(@org.springframework.data.repository.query.Param("since") java.time.LocalDateTime since);

    @org.springframework.data.jpa.repository.Query("""
        SELECT f.model, count(f),
               coalesce(sum(case when f.status = com.relyon.economizaai.model.enums.AiFindingStatus.APPROVED then 1 else 0 end),0),
               coalesce(sum(case when f.status = com.relyon.economizaai.model.enums.AiFindingStatus.REJECTED then 1 else 0 end),0),
               coalesce(avg(f.confidence),0)
        FROM AiFinding f
        WHERE f.model IS NOT NULL
        GROUP BY f.model
    """)
    List<Object[]> statsByModelAllTime();

    List<AiFinding> findByStatus(AiFindingStatus status);

    Page<AiFinding> findByStatusAndTypeOrderByCreatedAtDesc(AiFindingStatus status, AiFindingType type, Pageable pageable);

    /** Confidence-first ordering — the quick-accept review lists the surest suggestions on top. */
    Page<AiFinding> findByStatusAndTypeOrderByConfidenceDesc(AiFindingStatus status, AiFindingType type, Pageable pageable);

    /** All findings of a status+type (unpaged) — dedupe lookup before creating new suggestions. */
    List<AiFinding> findByStatusAndType(AiFindingStatus status, AiFindingType type);

    /** Suggestions confident enough to auto-approve in bulk ("Aceitar todos ≥ X%"). */
    List<AiFinding> findByStatusAndTypeAndConfidenceGreaterThanEqual(AiFindingStatus status, AiFindingType type, BigDecimal confidence);

    long countByStatus(AiFindingStatus status);

    boolean existsByTypeAndTitle(AiFindingType type, String title);

    boolean existsByTypeAndStatusAndTitleStartingWith(AiFindingType type, AiFindingStatus status, String titlePrefix);
}
