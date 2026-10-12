package com.relyon.economizaai.model.enums;

/** Jobs de notificação de sistema configuráveis pelo dashboard (notification_system_settings). */
public enum NotificationJob {
    /** Resumo semanal (DIGEST) — dia da semana + hora configuráveis. */
    WEEKLY_DIGEST,
    /** Resumo mensal (DIGEST) — dia do mês + hora configuráveis. */
    MONTHLY_DIGEST,
    /** Digest diário de ofertas (DEALS_DIGEST) — horário é por usuário; aqui só liga/desliga. */
    DEALS_DIGEST,
    /** Alerta de orçamento (BUDGET) — intervalo fixo; aqui só liga/desliga. */
    BUDGET_ALERT,
    /** Alerta de reposição (STOCKOUT) — intervalo fixo; aqui só liga/desliga. */
    REPLENISHMENT
}
