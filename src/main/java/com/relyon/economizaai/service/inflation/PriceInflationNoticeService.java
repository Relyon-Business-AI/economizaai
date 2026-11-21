package com.relyon.economizaai.service.inflation;

import com.relyon.economizaai.config.PriceInflationProperties;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationDestination;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.repository.OfficialInflationMonthRepository;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.LocalizedMessageService;
import com.relyon.economizaai.service.geo.ViewerLocationService;
import com.relyon.economizaai.service.inflation.CommunityIndexMath.Span;
import com.relyon.economizaai.service.inflation.CommunityIndexReader.WindowData;
import com.relyon.economizaai.service.notifications.NotificationPayload;
import com.relyon.economizaai.service.notifications.NotificationService;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The monthly notice of "Preços e inflação": when IBGE publishes a new month, each person the
 * feature exists for gets one inbox item saying how much food moved in Brasil and, when their
 * state has enough data that month, how the prices paid in its markets moved. Inbox only —
 * {@link NotificationType#PRICE_INFLATION} never goes out by push, e-mail or WhatsApp.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PriceInflationNoticeService {

    private static final int PAGE_SIZE = 500;
    private static final UUID BEFORE_FIRST_ID = new UUID(0L, 0L);
    private static final String ROSE = "rose";
    private static final String FELL = "fell";
    private static final String STABLE = "stable";

    private final PriceInflationProperties properties;
    private final PriceInflationAudienceGate audienceGate;
    private final OfficialInflationMonthRepository officialRepository;
    private final CommunityIndexReader communityReader;
    private final UserRepository userRepository;
    private final ViewerLocationService viewerLocationService;
    private final NotificationService notificationService;
    private final LocalizedMessageService messageService;

    /** Tells everyone the feature exists for about the month. Returns how many inbox items were created. */
    public int announce(YearMonth month) {
        if (!properties.isMonthlyNotice()) {
            log.info("price_inflation.notice.skipped month={} reason=switched_off", month);
            return 0;
        }
        var nationalChange = nationalChangeOf(month);
        if (nationalChange.isEmpty()) {
            log.warn("price_inflation.notice.skipped month={} reason=no_official_number", month);
            return 0;
        }
        var communityMonth = communityReader.read(new Span(month, month));
        var created = 0;
        var afterId = BEFORE_FIRST_ID;
        while (true) {
            var recipients = userRepository.findPriceInflationNoticePage(
                    !audienceGate.isOpenToEveryone(), afterId, PageRequest.of(0, PAGE_SIZE));
            if (recipients.isEmpty()) break;
            for (var recipient : recipients) {
                if (announceTo(recipient, month, nationalChange.get(), communityMonth)) created++;
            }
            afterId = recipients.get(recipients.size() - 1).getId();
        }
        log.info("price_inflation.notice.done month={} created={}", month, created);
        return created;
    }

    private Optional<BigDecimal> nationalChangeOf(YearMonth month) {
        return officialRepository.findByAreaCodeAndItemCodeAndReferenceMonth(OfficialInflationLoader.NATIONAL_AREA_CODE,
                        properties.getSidra().getFoodAtHomeItem(), month.atDay(1))
                .map(official -> CommunityIndexMath.oneDecimal(official.getChangePercent().doubleValue()));
    }

    /** One failed recipient never stops the others. */
    private boolean announceTo(User recipient, YearMonth month, BigDecimal nationalChange, WindowData communityMonth) {
        try {
            var locale = LocalizedMessageService.toLocale(recipient.getLocale());
            var body = nationalSentence(nationalChange, month, locale)
                    + stateChangeOf(recipient, month, communityMonth)
                            .map(stateChange -> " " + stateSentence(stateChange, locale)).orElse("");
            var saved = notificationService.notify(new NotificationPayload(recipient, NotificationType.PRICE_INFLATION,
                    messageService.translate("notification.price_inflation.title", locale), body,
                    Map.of("destination", NotificationDestination.PRICE_INFLATION.name(), "month", month.toString())));
            return saved != null;
        } catch (RuntimeException failure) {
            log.warn("price_inflation.notice.failed user={} reason={}",
                    LogMasker.email(recipient.getEmail()), failure.getMessage());
            return false;
        }
    }

    private record StateChange(String state, BigDecimal changePercent) {}

    /** The change in the recipient's state, only when the state has enough data that month. */
    private Optional<StateChange> stateChangeOf(User recipient, YearMonth month, WindowData communityMonth) {
        var state = viewerLocationService.majorityState(recipient);
        if (state == null || !communityMonth.isValid(month, state)) return Optional.empty();
        return Optional.ofNullable(communityMonth.links(state).get(month))
                .map(link -> new StateChange(state, CommunityIndexMath.oneDecimal((link - 1) * 100.0)));
    }

    private String nationalSentence(BigDecimal changePercent, YearMonth month, Locale locale) {
        return messageService.translate("notification.price_inflation.national." + direction(changePercent), locale,
                percentage(changePercent, locale), month.getMonth().getDisplayName(TextStyle.FULL, locale));
    }

    private String stateSentence(StateChange stateChange, Locale locale) {
        var stateReference = messageService.translate("notification.price_inflation.state_name." + stateChange.state(), locale);
        return messageService.translate("notification.price_inflation.state." + direction(stateChange.changePercent()),
                locale, stateReference, percentage(stateChange.changePercent(), locale));
    }

    private static String direction(BigDecimal changePercent) {
        if (changePercent.signum() > 0) return ROSE;
        return changePercent.signum() < 0 ? FELL : STABLE;
    }

    /** "0,4" in Portuguese, "0.4" in English — always the size of the move; the verb carries the direction. */
    private static String percentage(BigDecimal changePercent, Locale locale) {
        return new DecimalFormat("0.0", DecimalFormatSymbols.getInstance(locale)).format(changePercent.abs());
    }
}
