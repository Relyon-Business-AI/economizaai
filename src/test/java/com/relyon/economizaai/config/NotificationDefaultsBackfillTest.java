package com.relyon.economizaai.config;

import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.NotificationRuleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationDefaultsBackfillTest {

    @Mock private NotificationRuleRepository ruleRepository;
    @InjectMocks private NotificationDefaultsBackfill backfill;

    @Test
    void run_callsInsertOncePerDefaultType() {
        for (var type : NotificationType.defaults()) {
            when(ruleRepository.insertMissingDefaultRules(type.name())).thenReturn(1);
        }

        backfill.run(null);

        for (var type : NotificationType.defaults()) {
            verify(ruleRepository, times(1)).insertMissingDefaultRules(type.name());
        }
    }
}
