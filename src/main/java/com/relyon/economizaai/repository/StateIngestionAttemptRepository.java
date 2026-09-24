package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.StateIngestionAttempt;
import com.relyon.economizaai.model.enums.StateIngestionOutcome;
import com.relyon.economizaai.model.enums.StateIngestionStrategy;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface StateIngestionAttemptRepository extends JpaRepository<StateIngestionAttempt, UUID> {

    boolean existsByUfAndOutcome(UnidadeFederativa uf, StateIngestionOutcome outcome);

    boolean existsByUfAndAdminNotifiedTrueAndCreatedAtGreaterThanEqual(UnidadeFederativa uf, OffsetDateTime since);

    long countByUfAndStrategyAndOutcomeAndCreatedAtGreaterThanEqual(
            UnidadeFederativa uf, StateIngestionStrategy strategy, StateIngestionOutcome outcome, OffsetDateTime since);

    @Query("""
            select attempt.uf as uf, attempt.strategy as strategy, attempt.outcome as outcome,
                   count(attempt) as attempts, max(attempt.createdAt) as lastAttemptAt
            from StateIngestionAttempt attempt
            group by attempt.uf, attempt.strategy, attempt.outcome
            """)
    List<StateAttemptSummary> summarize();

    /** Same aggregation as {@link #summarize()} but only over attempts since {@code since} (period filter). */
    @Query("""
            select attempt.uf as uf, attempt.strategy as strategy, attempt.outcome as outcome,
                   count(attempt) as attempts, max(attempt.createdAt) as lastAttemptAt
            from StateIngestionAttempt attempt
            where attempt.createdAt >= :since
            group by attempt.uf, attempt.strategy, attempt.outcome
            """)
    List<StateAttemptSummary> summarizeSince(OffsetDateTime since);

    interface StateAttemptSummary {
        UnidadeFederativa getUf();
        StateIngestionStrategy getStrategy();
        StateIngestionOutcome getOutcome();
        long getAttempts();
        OffsetDateTime getLastAttemptAt();
    }
}
