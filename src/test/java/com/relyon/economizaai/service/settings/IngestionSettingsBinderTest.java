package com.relyon.economizaai.service.settings;

import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.repository.StateIngestionAttemptRepository;
import com.relyon.economizaai.security.ratelimit.ReceiptQuotas;
import com.relyon.economizaai.service.ContactService;
import com.relyon.economizaai.service.ReceiptBatchLimit;
import com.relyon.economizaai.service.sefaz.PortalRecoverySweeper;
import com.relyon.economizaai.service.sefaz.SefazIngestionService;
import com.relyon.economizaai.service.sefaz.StateCoverageService;
import com.relyon.economizaai.service.sefaz.StateStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class IngestionSettingsBinderTest {

    @Mock private ReceiptRepository receiptRepository;
    @Mock private StateIngestionAttemptRepository attemptRepository;
    @Mock private SefazIngestionService sefazIngestionService;
    @Mock private ContactService contactService;

    private final ReceiptQuotas quotas = new ReceiptQuotas();
    private final ReceiptBatchLimit batchLimit = new ReceiptBatchLimit();
    private final SettingBindings bindings = new SettingBindings();
    private PortalRecoverySweeper portalRecoverySweeper;
    private StateStatusService stateStatus;
    private StateCoverageService stateCoverage;

    @BeforeEach
    void bind() {
        portalRecoverySweeper = new PortalRecoverySweeper(receiptRepository, 336);
        stateStatus = new StateStatusService(sefazIngestionService, receiptRepository, attemptRepository, "", 24, 3, 5, 300);
        stateCoverage = new StateCoverageService(attemptRepository, receiptRepository, contactService, 5, 6, 5, 2, 2);
        new IngestionSettingsBinder(quotas, batchLimit, portalRecoverySweeper, stateStatus, stateCoverage).bind(bindings);
    }

    private String valueOf(RuntimeSetting setting) {
        return bindings.of(setting).currentValue().get();
    }

    private void change(RuntimeSetting setting, String value) {
        bindings.of(setting).replaceValue().accept(value);
    }

    @Test
    void everySettingStartsAtTheValueInUseToday() {
        assertThat(valueOf(RuntimeSetting.RECEIPT_SUBMITS_PER_HOUR)).isEqualTo("30");
        assertThat(valueOf(RuntimeSetting.RECEIPT_IMPORTS_PER_HOUR)).isEqualTo("5");
        assertThat(valueOf(RuntimeSetting.IMPORT_PREVIEWS_PER_HOUR)).isEqualTo("30");
        assertThat(valueOf(RuntimeSetting.HISTORY_EXPORTS_PER_HOUR)).isEqualTo("10");
        assertThat(valueOf(RuntimeSetting.BULK_MAX_RECEIPTS)).isEqualTo("500");
        assertThat(valueOf(RuntimeSetting.PORTAL_RETRY_HOURS)).isEqualTo("336");
        assertThat(valueOf(RuntimeSetting.STATE_UNSTABLE_WINDOW_HOURS)).isEqualTo("24");
        assertThat(valueOf(RuntimeSetting.STATE_UNSTABLE_MIN_FAILURES)).isEqualTo("3");
        assertThat(valueOf(RuntimeSetting.PORTAL_REGRESSION_MIN_FAILURES)).isEqualTo("5");
        assertThat(valueOf(RuntimeSetting.PORTAL_REGRESSION_WINDOW_HOURS)).isEqualTo("6");
        assertThat(valueOf(RuntimeSetting.STATE_EVIDENCE_USER_DAYS)).isEqualTo("5");
        assertThat(valueOf(RuntimeSetting.STATE_EVIDENCE_USERS)).isEqualTo("2");
        assertThat(valueOf(RuntimeSetting.STATE_EVIDENCE_DAYS)).isEqualTo("2");
    }

    @Test
    void aSavedValue_reachesTheCodeThatUsesIt() {
        change(RuntimeSetting.RECEIPT_SUBMITS_PER_HOUR, "60");
        change(RuntimeSetting.RECEIPT_IMPORTS_PER_HOUR, "8");
        change(RuntimeSetting.IMPORT_PREVIEWS_PER_HOUR, "40");
        change(RuntimeSetting.HISTORY_EXPORTS_PER_HOUR, "20");
        change(RuntimeSetting.BULK_MAX_RECEIPTS, "800");
        change(RuntimeSetting.PORTAL_RETRY_HOURS, "168");
        change(RuntimeSetting.STATE_UNSTABLE_WINDOW_HOURS, "48");
        change(RuntimeSetting.STATE_UNSTABLE_MIN_FAILURES, "6");
        change(RuntimeSetting.PORTAL_REGRESSION_WINDOW_HOURS, "12");
        change(RuntimeSetting.STATE_EVIDENCE_USER_DAYS, "9");
        change(RuntimeSetting.STATE_EVIDENCE_USERS, "3");
        change(RuntimeSetting.STATE_EVIDENCE_DAYS, "4");

        assertThat(quotas.getReceiptSubmitsPerHour()).isEqualTo(60);
        assertThat(quotas.getReceiptImportsPerHour()).isEqualTo(8);
        assertThat(quotas.getImportPreviewsPerHour()).isEqualTo(40);
        assertThat(quotas.getHistoryExportsPerHour()).isEqualTo(20);
        assertThat(batchLimit.getMaxReceipts()).isEqualTo(800);
        assertThat(portalRecoverySweeper.getWindowHours()).isEqualTo(168);
        assertThat(stateStatus.getWindowHours()).isEqualTo(48);
        assertThat(stateStatus.getMinFailures()).isEqualTo(6);
        assertThat(stateCoverage.getRegressionWindowHours()).isEqualTo(12);
        assertThat(stateCoverage.getMinEvidenceUserDays()).isEqualTo(9);
        assertThat(stateCoverage.getMinEvidenceUsers()).isEqualTo(3);
        assertThat(stateCoverage.getMinEvidenceDays()).isEqualTo(4);
    }

    @Test
    void thePortalChangeThreshold_isOneNumberForTheAlertAndTheStateStatus() {
        change(RuntimeSetting.PORTAL_REGRESSION_MIN_FAILURES, "9");

        assertThat(stateCoverage.getRegressionMinFailures()).isEqualTo(9);
        assertThat(ReflectionTestUtils.getField(stateStatus, "regressionMinFailures")).isEqualTo(9);
    }
}
