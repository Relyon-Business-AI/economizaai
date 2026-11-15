package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.model.Notification;
import com.relyon.economizaai.model.SpendingReportDelivery;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.SpendingReportFrequency;
import com.relyon.economizaai.repository.SpendingReportDeliveryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Send-once guard and history of the spending reports. A report is claimed BEFORE it is sent:
 * the claim is an insert under the unique (user, frequency, period) constraint, so only one run
 * of a period wins it — an overlap, a restart or a second instance loses the insert and skips.
 * A claim that ended up sending nothing is released, so the history only holds reports that
 * were actually handed to the user's channels.
 */
@Service
@RequiredArgsConstructor
public class SpendingReportDeliveryLedger {

    private final SpendingReportDeliveryRepository deliveryRepository;

    /** The claimed delivery, or empty when this period's report was already claimed for the user. */
    public Optional<SpendingReportDelivery> claim(User user, SpendingReportFrequency frequency, LocalDate periodStart) {
        try {
            return Optional.of(deliveryRepository.saveAndFlush(SpendingReportDelivery.builder()
                    .user(user)
                    .frequency(frequency)
                    .periodStart(periodStart)
                    .build()));
        } catch (DataIntegrityViolationException alreadyClaimed) {
            return Optional.empty();
        }
    }

    public void recordNotification(SpendingReportDelivery delivery, Notification notification) {
        delivery.setNotification(notification);
        deliveryRepository.save(delivery);
    }

    public void release(SpendingReportDelivery delivery) {
        deliveryRepository.delete(delivery);
    }
}
