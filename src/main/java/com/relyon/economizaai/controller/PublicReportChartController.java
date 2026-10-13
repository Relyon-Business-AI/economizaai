package com.relyon.economizaai.controller;

import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.notifications.ReportChartTokenService;
import com.relyon.economizaai.service.notifications.WhatsAppReportChartService;
import com.relyon.economizaai.service.report.PurchaseReportAssembler;
import com.relyon.economizaai.time.BrazilClock;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/**
 * Serves a user's last-7-days category pie chart as a PNG over an UNAUTHENTICATED URL,
 * so Twilio can fetch it as a WhatsApp {@code MediaUrl} (it fetches with no credentials).
 * Access is gated by a short-lived HMAC token ({@link ReportChartTokenService}) — the only
 * data exposed is an aggregate spend-by-category chart (no PII), and the token expires fast.
 * The chart is regenerated on each fetch (no storage, nothing to clean up).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/public/report-charts")
@RequiredArgsConstructor
@Tag(name = "Public", description = "Endpoints públicos (sem autenticação), gated por token")
public class PublicReportChartController {

    private final ReportChartTokenService tokenService;
    private final UserRepository userRepository;
    private final PurchaseReportAssembler reportAssembler;
    private final WhatsAppReportChartService chartService;

    @Operation(summary = "Pizza de gastos por categoria (PNG) de um usuário, gated por token HMAC curto")
    @GetMapping(value = "/{token}.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> categoryPie(@PathVariable String token) {
        var userId = tokenService.verify(token).orElse(null);
        if (userId == null) {
            return ResponseEntity.notFound().build();
        }
        var user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }

        var to = BrazilClock.nowDateTime();
        var report = reportAssembler.assemble(user, to.minusDays(7), to);
        var locale = user.getLocale() == null || user.getLocale().isBlank()
                ? Locale.forLanguageTag("pt")
                : Locale.forLanguageTag(user.getLocale());
        var png = chartService.renderCategoryPie(report, locale);
        if (png == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noCache())
                .body(png);
    }
}
