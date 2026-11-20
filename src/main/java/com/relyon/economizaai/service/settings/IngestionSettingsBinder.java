package com.relyon.economizaai.service.settings;

import com.relyon.economizaai.security.ratelimit.ReceiptQuotas;
import com.relyon.economizaai.service.ReceiptBatchLimit;
import com.relyon.economizaai.service.sefaz.PortalRecoverySweeper;
import com.relyon.economizaai.service.sefaz.StateCoverageService;
import com.relyon.economizaai.service.sefaz.StateStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Receipt quotas and batch size, the portal-outage deadline and what makes a state unstable or proven broken. */
@Component
@RequiredArgsConstructor
public class IngestionSettingsBinder implements SettingBinder {

    private final ReceiptQuotas quotas;
    private final ReceiptBatchLimit batchLimit;
    private final PortalRecoverySweeper portalRecoverySweeper;
    private final StateStatusService stateStatus;
    private final StateCoverageService stateCoverage;

    @Override
    public void bind(SettingBindings bindings) {
        bindQuotas(bindings);
        bindStateHealth(bindings);
    }

    private void bindQuotas(SettingBindings bindings) {
        bindings.integer(RuntimeSetting.RECEIPT_SUBMITS_PER_HOUR,
                quotas::getReceiptSubmitsPerHour, quotas::setReceiptSubmitsPerHour);
        bindings.integer(RuntimeSetting.RECEIPT_IMPORTS_PER_HOUR,
                quotas::getReceiptImportsPerHour, quotas::setReceiptImportsPerHour);
        bindings.integer(RuntimeSetting.IMPORT_PREVIEWS_PER_HOUR,
                quotas::getImportPreviewsPerHour, quotas::setImportPreviewsPerHour);
        bindings.integer(RuntimeSetting.HISTORY_EXPORTS_PER_HOUR,
                quotas::getHistoryExportsPerHour, quotas::setHistoryExportsPerHour);
        bindings.integer(RuntimeSetting.BULK_MAX_RECEIPTS, batchLimit::getMaxReceipts, batchLimit::setMaxReceipts);
        bindings.integer(RuntimeSetting.PORTAL_RETRY_HOURS,
                portalRecoverySweeper::getWindowHours, portalRecoverySweeper::setWindowHours);
    }

    private void bindStateHealth(SettingBindings bindings) {
        bindings.integer(RuntimeSetting.STATE_UNSTABLE_WINDOW_HOURS, stateStatus::getWindowHours, stateStatus::setWindowHours);
        bindings.integer(RuntimeSetting.STATE_UNSTABLE_MIN_FAILURES, stateStatus::getMinFailures, stateStatus::setMinFailures);
        bindings.integer(RuntimeSetting.PORTAL_REGRESSION_MIN_FAILURES,
                stateCoverage::getRegressionMinFailures, this::changeRegressionMinFailures);
        bindings.wholeNumber(RuntimeSetting.PORTAL_REGRESSION_WINDOW_HOURS,
                stateCoverage::getRegressionWindowHours, stateCoverage::setRegressionWindowHours);
        bindings.integer(RuntimeSetting.STATE_EVIDENCE_USER_DAYS,
                stateCoverage::getMinEvidenceUserDays, stateCoverage::setMinEvidenceUserDays);
        bindings.integer(RuntimeSetting.STATE_EVIDENCE_USERS,
                stateCoverage::getMinEvidenceUsers, stateCoverage::setMinEvidenceUsers);
        bindings.integer(RuntimeSetting.STATE_EVIDENCE_DAYS,
                stateCoverage::getMinEvidenceDays, stateCoverage::setMinEvidenceDays);
    }

    /** One number for the admin alert and for the "situação dos estados" answer. */
    private void changeRegressionMinFailures(int minFailures) {
        stateCoverage.setRegressionMinFailures(minFailures);
        stateStatus.setRegressionMinFailures(minFailures);
    }
}
