package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.InfosimplesMonthHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InfosimplesMonthHistoryRepository extends JpaRepository<InfosimplesMonthHistory, UUID> {

    Optional<InfosimplesMonthHistory> findByMonth(String month);

    List<InfosimplesMonthHistory> findAllByOrderByMonthDesc();
}
