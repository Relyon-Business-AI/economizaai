package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.ConsensusGraduationAudit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ConsensusGraduationAuditRepository extends JpaRepository<ConsensusGraduationAudit, UUID> {

    List<ConsensusGraduationAudit> findByProductIdOrderByCreatedAtDesc(UUID productId);

    List<ConsensusGraduationAudit> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
