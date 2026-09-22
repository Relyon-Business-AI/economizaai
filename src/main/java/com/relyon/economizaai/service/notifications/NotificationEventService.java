package com.relyon.economizaai.service.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.model.NotificationEvent;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationEventType;
import com.relyon.economizaai.repository.NotificationEventRepository;
import com.relyon.economizaai.repository.NotificationRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Single entry point for the notification telemetry log. Every user-feedback /
 * lifecycle signal (client-reported or server-emitted) is recorded here so a
 * future ranking/learning engine has training data. Nothing consumes these
 * rows yet — this is the telemetry foundation only.
 *
 * <p>The digest / screen / attribution phases will call this for
 * {@code SENT}/{@code DELIVERED}/{@code CONVERTED} etc.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationEventService {

    private final NotificationEventRepository repository;
    private final ProductRepository productRepository;
    private final NotificationRepository notificationRepository;
    // Plain instance (not the Spring bean) to mirror NotificationService and stay
    // wireable in @WebMvcTest slices that don't load JacksonAutoConfiguration.
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Optional context for a recorded event. All fields are nullable; only the
     * user and event type are mandatory (passed separately to {@link #record}).
     */
    @Builder
    public record RecordContext(
            UUID notificationId,
            UUID productId,
            String marketCnpj,
            String channel,
            Map<String, Object> metadata,
            BigDecimal savingsAmount
    ) {
        public static RecordContext empty() {
            return RecordContext.builder().build();
        }
    }

    @Transactional
    public NotificationEvent record(User user, NotificationEventType type, RecordContext context) {
        var safeContext = context != null ? context : RecordContext.empty();
        var productId = resolveProductId(safeContext.productId());
        var notificationId = resolveNotificationId(safeContext.notificationId());
        var event = NotificationEvent.builder()
                .user(user)
                .eventType(type)
                .notificationId(notificationId)
                .productId(productId)
                .marketCnpj(safeContext.marketCnpj())
                .channel(safeContext.channel())
                .metadata(serialize(safeContext.metadata()))
                .savingsAmount(safeContext.savingsAmount())
                .build();
        var saved = repository.save(event);
        log.info("notif.event type={} user={}", type, LogMasker.email(user.getEmail()));
        return saved;
    }

    @Transactional
    public NotificationEvent record(User user, NotificationEventType type) {
        return record(user, type, RecordContext.empty());
    }

    /**
     * {@code product_id} is a loose reference guarded by a DB FK
     * ({@code notification_events_product_id_fkey}). A client may report an event
     * for a product id that isn't (or is no longer) a canonical product; persisting
     * it as-is fails the insert. Drop the unknown link to null so the behavioral
     * signal is still recorded.
     */
    private UUID resolveProductId(UUID productId) {
        if (productId == null || productRepository.existsById(productId)) {
            return productId;
        }
        log.warn("notif.event.unknown_product product={}", productId);
        return null;
    }

    /**
     * Same loose-reference guard for {@code notification_id} (FK
     * {@code notification_events_notification_id_fkey}): a client can report an
     * event tied to a push that no longer exists, which would otherwise fail the
     * insert. Drop the unknown link to null so the signal is still recorded.
     */
    private UUID resolveNotificationId(UUID notificationId) {
        if (notificationId == null || notificationRepository.existsById(notificationId)) {
            return notificationId;
        }
        log.warn("notif.event.unknown_notification notification={}", notificationId);
        return null;
    }

    private String serialize(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception ex) {
            log.warn("notif.event.metadata_serialize_failed reason={}", ex.getMessage());
            return null;
        }
    }
}
