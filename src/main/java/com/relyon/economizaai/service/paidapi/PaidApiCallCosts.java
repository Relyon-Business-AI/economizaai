package com.relyon.economizaai.service.paidapi;

import com.relyon.economizaai.model.enums.PaidApiService;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * What one call to each paid service is assumed to cost, in cents (R$). The single source for
 * the ledger ({@code paid_api_call.estimated_cost_cents}), and through it for the daily budgets
 * and the cost reports; the prepaid-balance watch also uses it as "the price of one more call".
 * Changing a cost only affects calls recorded from then on.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "economizaai.paid-api.call-cost-cents")
public class PaidApiCallCosts {

    private volatile int infosimples = 24;
    private volatile int captcha = 3;
    private volatile int twilio = 30;
    private volatile int llmEnrich = 2;
    private volatile int llmVision = 10;
    private volatile int llmHtmlRescue = 5;

    public int cents(PaidApiService service) {
        return switch (service) {
            case INFOSIMPLES -> infosimples;
            case CAPTCHA_SOLVE -> captcha;
            case TWILIO_MESSAGE -> twilio;
            case LLM_ENRICH -> llmEnrich;
            case LLM_VISION -> llmVision;
            case LLM_HTML_RESCUE -> llmHtmlRescue;
        };
    }

    public BigDecimal reais(PaidApiService service) {
        return BigDecimal.valueOf(cents(service)).movePointLeft(2);
    }

    /** The price of one call in US$, rounded UP to the cent — for providers whose balance is held in dollars. */
    public BigDecimal dollars(PaidApiService service, BigDecimal reaisPerDollar) {
        return reais(service).divide(reaisPerDollar, 2, RoundingMode.CEILING);
    }
}
