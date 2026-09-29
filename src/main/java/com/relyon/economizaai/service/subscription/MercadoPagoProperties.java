package com.relyon.economizaai.service.subscription;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

/**
 * Mercado Pago web-checkout config (assinatura mensal via preapproval). The
 * whole feature is INERT until credentialed: a blank {@code accessToken}
 * disables checkout creation (503), a blank {@code webhookSecret} disables the
 * webhook (fail-closed reject). Activate by setting {@code MP_ACCESS_TOKEN} /
 * {@code MP_WEBHOOK_SECRET} in the {@code economizaai.billing.mercadopago}
 * block.
 */
@Configuration
@ConfigurationProperties(prefix = "economizaai.billing.mercadopago")
public class MercadoPagoProperties {

    private String accessToken = "";
    private String webhookSecret = "";
    private BigDecimal planAmount = new BigDecimal("9.90");
    private String planReason = "Economiza AI Premium";
    private String backUrl = "https://economizaai.app/premium/obrigado";
    private String apiBaseUrl = "https://api.mercadopago.com";

    public boolean isCheckoutConfigured() {
        return accessToken != null && !accessToken.isBlank();
    }

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String v) { this.accessToken = v; }

    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String v) { this.webhookSecret = v; }

    public BigDecimal getPlanAmount() { return planAmount; }
    public void setPlanAmount(BigDecimal v) { this.planAmount = v; }

    public String getPlanReason() { return planReason; }
    public void setPlanReason(String v) { this.planReason = v; }

    public String getBackUrl() { return backUrl; }
    public void setBackUrl(String v) { this.backUrl = v; }

    public String getApiBaseUrl() { return apiBaseUrl; }
    public void setApiBaseUrl(String v) { this.apiBaseUrl = v; }
}
