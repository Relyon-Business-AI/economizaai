package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.NotificationNoticeOption;
import com.relyon.economizaai.model.enums.NotificationNotice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface NotificationNoticeOptionRepository extends JpaRepository<NotificationNoticeOption, UUID> {

    List<NotificationNoticeOption> findAllByUserId(UUID userId);

    List<NotificationNoticeOption> findAllByUserIdAndNotice(UUID userId, NotificationNotice notice);
}
