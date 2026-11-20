package com.relyon.economizaai.service.settings;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** The groups the admin shows the settings in, in display order. */
@Getter
@RequiredArgsConstructor
public enum SettingSection {
    PLANS("Planos e assinatura",
            "Limites do plano grátis, preços, promoção de lançamento e indicação."),
    PRICES("Preços e ofertas",
            "Por quanto tempo um preço vale, o que conta como promoção e o que é perto."),
    SAVINGS("Economia e previsão de consumo",
            "Como a economia é calculada e quando um produto está acabando."),
    NOTIFICATIONS("Notificações",
            "Horário de silêncio, relevância das ofertas e lembretes."),
    COSTS("Custos e limites de uso",
            "Tetos diários das APIs pagas, por usuário e por serviço."),
    RECEIPTS("Notas e estados",
            "Estados atendidos, prazos de reprocessamento e confirmação automática."),
    CATEGORIZATION("Categorização de produtos",
            "Quão certa a IA precisa estar e quando o sistema aprende uma categoria sozinho."),
    APP("Aplicativo",
            "Versão mínima, avaliação na loja e fotos por nota."),
    MERCHANT("Comerciantes",
            "Conta de comerciante: promoção de lançamento, reivindicação e importação.");

    private final String label;
    private final String description;
}
