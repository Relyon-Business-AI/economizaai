package com.relyon.economizaai.service.settings;

import com.relyon.economizaai.service.PendingReceiptAutoConfirmer;
import com.relyon.economizaai.service.llm.PhotoReceiptExtractionService;
import com.relyon.economizaai.service.paidapi.InfosimplesAlertService;
import com.relyon.economizaai.service.sefaz.ContingencyResyncSweeper;
import com.relyon.economizaai.service.sefaz.PausedStateRecoveryProbe;
import com.relyon.economizaai.service.sefaz.PrefetchPolicy;
import com.relyon.economizaai.service.sefaz.StateStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Receipts and states: paused states, on-device fetch, auto-confirm, deadlines. */
@Component
@RequiredArgsConstructor
public class ReceiptSettingsBinder implements SettingBinder {

    /** Only exists while the Infosimples fallback is enabled. */
    private final Optional<PausedStateRecoveryProbe> pausedStateProbe;
    private final StateStatusService stateStatus;
    private final InfosimplesAlertService infosimplesAlerts;
    private final PrefetchPolicy prefetchPolicy;
    private final PendingReceiptAutoConfirmer autoConfirmer;
    private final ContingencyResyncSweeper contingencySweeper;
    private final PhotoReceiptExtractionService photoExtraction;

    @Override
    public void bind(SettingBindings bindings) {
        bindings.text(RuntimeSetting.PAUSED_STATES, stateStatus::pausedStatesCsv, this::changePausedStates);
        bindings.text(RuntimeSetting.DEVICE_FETCH_TRUSTED_STATES, prefetchPolicy::trustedUfsCsv, prefetchPolicy::changeTrustedUfs);
        bindings.toggle(RuntimeSetting.DEVICE_FETCH_GENERIC_ENABLED,
                prefetchPolicy::isGenericEnabled, prefetchPolicy::setGenericEnabled);
        bindings.toggle(RuntimeSetting.AUTO_CONFIRM_ENABLED, autoConfirmer::isEnabled, autoConfirmer::setEnabled);
        bindings.integer(RuntimeSetting.AUTO_CONFIRM_AFTER_HOURS, autoConfirmer::getAfterHours, autoConfirmer::setAfterHours);
        bindings.integer(RuntimeSetting.CONTINGENCY_WINDOW_HOURS,
                contingencySweeper::getWindowHours, contingencySweeper::setWindowHours);
        bindings.integer(RuntimeSetting.MAX_ITEM_PHOTOS, photoExtraction::getMaxPhotos, photoExtraction::setMaxPhotos);
    }

    /** One list of paused states: the probe, the "situação dos estados" answer and the Infosimples alerts. */
    private void changePausedStates(String ufsCsv) {
        pausedStateProbe.ifPresent(probe -> probe.changeWatchedUfs(ufsCsv));
        stateStatus.changePausedStates(ufsCsv);
        infosimplesAlerts.changeKnownOfflineUfs(ufsCsv);
    }
}
