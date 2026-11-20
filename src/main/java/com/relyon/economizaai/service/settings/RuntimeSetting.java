package com.relyon.economizaai.service.settings;

import com.relyon.economizaai.model.enums.RelevanceMode;
import com.relyon.economizaai.service.subscription.Feature;
import lombok.Getter;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.relyon.economizaai.service.settings.SettingSection.APP;
import static com.relyon.economizaai.service.settings.SettingSection.COSTS;
import static com.relyon.economizaai.service.settings.SettingSection.MERCHANT;
import static com.relyon.economizaai.service.settings.SettingSection.NOTIFICATIONS;
import static com.relyon.economizaai.service.settings.SettingSection.PLANS;
import static com.relyon.economizaai.service.settings.SettingSection.PRICES;
import static com.relyon.economizaai.service.settings.SettingSection.RECEIPTS;
import static com.relyon.economizaai.service.settings.SettingSection.SAVINGS;
import static com.relyon.economizaai.service.settings.SettingSpec.date;
import static com.relyon.economizaai.service.settings.SettingSpec.decimal;
import static com.relyon.economizaai.service.settings.SettingSpec.integer;
import static com.relyon.economizaai.service.settings.SettingSpec.integerList;
import static com.relyon.economizaai.service.settings.SettingSpec.option;
import static com.relyon.economizaai.service.settings.SettingSpec.optionList;
import static com.relyon.economizaai.service.settings.SettingSpec.text;
import static com.relyon.economizaai.service.settings.SettingSpec.toggle;
import static com.relyon.economizaai.service.settings.SettingSpec.ufList;

/**
 * Every business setting the admin can change. The key is the configuration property that
 * supplies the value when nothing was changed in the admin (application.yaml / env var), so a
 * setting has ONE name everywhere. Labels and descriptions are what the admin screen shows.
 */
@Getter
public enum RuntimeSetting {

    // ---- Planos e assinatura ----
    SUBSCRIPTION_ENFORCE("economizaai.subscription.enforce", PLANS, toggle(),
            "Cobrar pelo Premium (paywall)",
            "Desligado, todo mundo usa tudo. Ligado, valem os limites do plano grátis abaixo."),
    FREE_MONTHLY_RECEIPTS("economizaai.subscription.free.monthly-receipts", PLANS, integer(0, 1000).unit("notas/mês").app("freeMonthlyReceipts"),
            "Plano grátis: notas por mês",
            "Quantas notas quem está no plano grátis pode enviar em cada mês."),
    FREE_HISTORY_DAYS("economizaai.subscription.free.history-days", PLANS, integer(7, 3650).unit("dias").app("freeHistoryDays"),
            "Plano grátis: histórico",
            "Quantos dias para trás o plano grátis enxerga nos gastos e no histórico."),
    FREE_WATCHED_MARKETS("economizaai.subscription.free.watched-markets", PLANS, integer(0, 100).unit("mercados").app("freeWatchedMarkets"),
            "Plano grátis: mercados acompanhados",
            "Quantos mercados o plano grátis pode acompanhar."),
    FREE_HOUSEHOLD_MEMBERS("economizaai.subscription.free.household-members", PLANS, integer(1, 20).unit("pessoas"),
            "Plano grátis: pessoas na casa",
            "Tamanho da casa quando ninguém nela é Premium."),
    FREE_CUSTOM_CATEGORIES("economizaai.subscription.free.custom-categories", PLANS, integer(0, 100).unit("categorias"),
            "Plano grátis: categorias próprias",
            "Quantas categorias criadas pelo usuário a casa pode ter no plano grátis."),
    FREE_OPEN_FEATURES("economizaai.subscription.free.open-features", PLANS, optionList(featureNames()),
            "Recursos Premium liberados no grátis",
            "Recursos que continuam abertos a todos mesmo com o paywall ligado."),
    HOUSEHOLD_MEMBERS_MAX("economizaai.subscription.household-members-max", PLANS, integer(2, 20).unit("pessoas"),
            "Máximo de pessoas por casa",
            "Teto de qualquer casa, inclusive Premium."),
    PAID_GRACE_DAYS("economizaai.subscription.paid-grace-days", PLANS, integer(0, 30).unit("dias"),
            "Tolerância após o vencimento",
            "Dias de espera pela renovação antes de rebaixar uma assinatura paga."),
    PREMIUM_MONTHLY_PRICE("economizaai.monetization.monthly-price", PLANS, decimal("0.50", "999.00").unit("R$").app("premiumMonthlyPrice"),
            "Preço mensal do Premium",
            "Valor cobrado na assinatura pela web (Mercado Pago) e usado nas projeções de receita. "
                    + "O preço dentro do app vem da App Store e do Google Play."),
    PREMIUM_YEARLY_PRICE("economizaai.monetization.yearly-price", PLANS, decimal("0.50", "9999.00").unit("R$").app("premiumYearlyPrice"),
            "Preço anual do Premium",
            "Usado nas projeções de receita do painel."),
    LAUNCH_PROMO_ENABLED("economizaai.subscription.promo.enabled", PLANS, toggle(),
            "Promoção de lançamento ativa",
            "Dá Premium de graça a quem confirma a primeira nota."),
    LAUNCH_PROMO_UNTIL("economizaai.subscription.promo.until", PLANS, date().app("launchPromoUntil"),
            "Promoção de lançamento: até quando",
            "Último dia do Premium grátis de lançamento. Depois dessa data ninguém mais ganha. "
                    + "Quem já ganhou mantém a data que recebeu."),
    LAUNCH_PROMO_MONTHS("economizaai.subscription.promo.months", PLANS, integer(1, 36).unit("meses"),
            "Promoção de lançamento: meses",
            "Duração do Premium grátis quando não há data final definida."),
    LAUNCH_PROMO_WARNINGS_ENABLED("economizaai.subscription.promo.warnings-enabled", PLANS, toggle(),
            "Avisar que a promoção está acabando",
            "Envia os avisos de fim do Premium grátis."),
    LAUNCH_PROMO_WARNING_DAYS("economizaai.subscription.promo.warning-days", PLANS, integerList(1, 90).unit("dias antes"),
            "Avisos de fim da promoção",
            "Quantos dias antes do fim cada aviso é enviado."),

    // ---- Preços e ofertas ----
    PRICE_WINDOW_DAYS("economizaai.collaborative.lookback-days", PRICES, integer(7, 365).unit("dias").app("priceWindowDays"),
            "Validade dos preços da comunidade",
            "Um preço mais antigo que isso não aparece em lugar nenhum: plano de compras, melhores mercados, "
                    + "preço típico, mapa e preços de um mercado."),
    MARKET_PRICES_DEFAULT_DAYS("economizaai.collaborative.market-prices-default-days", PRICES,
            integer(1, 365).unit("dias").app("marketPricesDefaultDays"),
            "Preços de um mercado: período inicial",
            "Período com que a tela de preços de um mercado abre. Nunca passa da validade dos preços."),
    DEALS_MAX_PRICE_AGE_DAYS("economizaai.collaborative.deals-max-price-age-days", PRICES, integer(1, 365).unit("dias"),
            "Ofertas pra você: idade máxima do preço",
            "O preço mais recente de uma rede precisa ter no máximo essa idade para virar oferta."),
    NEARBY_RADIUS_KM("economizaai.collaborative.best-markets-nearby-radius-km", PRICES,
            decimal("1", "200").unit("km").app("nearbyRadiusKm"),
            "Raio de \"perto de você\"",
            "Distância usada em melhores mercados, ofertas, promoções e busca de produtos quando o app não informa outra."),
    COMMUNITY_PROMO_THRESHOLD_PCT("economizaai.collaborative.community-promo-threshold-pct", PRICES, integer(1, 90).unit("%"),
            "Promoção da comunidade: queda mínima",
            "Quanto abaixo do preço típico o preço recente precisa estar."),
    COMMUNITY_PROMO_RECENT_WINDOW_DAYS("economizaai.collaborative.community-promo-recent-window-days", PRICES,
            integer(1, 60).unit("dias"),
            "Promoção da comunidade: o que é recente",
            "Preços dos últimos dias contam como recentes; os mais antigos formam o preço típico."),
    COMMUNITY_PROMO_MIN_BASELINE("economizaai.collaborative.min-observations-for-community-promo", PRICES,
            integer(1, 1000).unit("preços"),
            "Promoção da comunidade: histórico mínimo",
            "Quantos preços antigos são necessários para conhecer o preço típico."),
    COMMUNITY_PROMO_MIN_RECENT("economizaai.collaborative.community-promo-min-recent-observations", PRICES,
            integer(1, 100).unit("preços"),
            "Promoção da comunidade: preços recentes mínimos",
            "Um preço baixo isolado não vira promoção; precisa se repetir."),
    MIN_OBSERVATIONS_PER_PRODUCT_MARKET("economizaai.collaborative.min-observations-per-product-market", PRICES,
            integer(1, 100).unit("preços"),
            "Preço de referência: observações mínimas",
            "Quantos preços de um produto num mercado são necessários para mostrar o preço de referência "
                    + "fora dos itens de mercado comuns."),
    PERSONAL_PROMO_THRESHOLD_PCT("economizaai.personal-promo.threshold-pct", PRICES, integer(1, 90).unit("%"),
            "Promoção pessoal: queda mínima",
            "Quanto abaixo do que a própria casa costuma pagar."),
    PERSONAL_PROMO_MIN_PURCHASES("economizaai.personal-promo.min-purchases-for-baseline", PRICES,
            integer(1, 100).unit("compras"),
            "Promoção pessoal: compras mínimas",
            "Compras anteriores necessárias para saber quanto a casa costuma pagar."),
    DEAL_MAX_DROP_PCT("economizaai.notifications.relevance-threshold.max-drop-pct", PRICES, integer(1, 90).unit("%"),
            "Oferta relevante: queda exigida em itens baratos",
            "Queda mínima para um item no preço baixo de referência virar oferta."),
    DEAL_MIN_DROP_PCT("economizaai.notifications.relevance-threshold.min-drop-pct", PRICES, integer(1, 90).unit("%"),
            "Oferta relevante: queda exigida em itens caros",
            "Queda mínima para um item no preço alto de referência virar oferta."),
    DEAL_LOW_PRICE_ANCHOR("economizaai.notifications.relevance-threshold.low-price", PRICES,
            decimal("0.10", "1000").unit("R$"),
            "Oferta relevante: preço baixo de referência",
            "Até esse preço vale a queda exigida de itens baratos."),
    DEAL_HIGH_PRICE_ANCHOR("economizaai.notifications.relevance-threshold.high-price", PRICES,
            decimal("1", "100000").unit("R$"),
            "Oferta relevante: preço alto de referência",
            "A partir desse preço vale a queda exigida de itens caros; no meio, a exigência cai aos poucos."),

    // ---- Economia e previsão de consumo ----
    SAVINGS_REGION_WINDOW_DAYS("economizaai.collaborative.savings-region-window-days", SAVINGS, integer(1, 180).unit("dias"),
            "Economia: janela de comparação",
            "Uma compra é comparada com o que outras casas pagaram tantos dias antes e depois."),
    SAVINGS_MIN_REGION_SAMPLES("economizaai.collaborative.savings-min-region-samples", SAVINGS, integer(1, 100).unit("preços"),
            "Economia: preços mínimos de outras casas",
            "Abaixo disso a comparação usa o histórico da própria casa."),
    SAVINGS_OWN_LOOKBACK_DAYS("economizaai.collaborative.savings-own-lookback-days", SAVINGS, integer(7, 730).unit("dias"),
            "Economia: histórico da própria casa",
            "Quantos dias para trás valem as compras anteriores da casa."),
    SAVINGS_MIN_OWN_SAMPLES("economizaai.collaborative.savings-min-own-samples", SAVINGS, integer(1, 100).unit("compras"),
            "Economia: compras próprias mínimas",
            "Compras anteriores necessárias para comparar com o histórico da casa."),
    SAVINGS_MAX_PRICE_RATIO("economizaai.collaborative.savings-max-price-ratio", SAVINGS, decimal("1.1", "20").unit("vezes"),
            "Economia: diferença máxima aceita",
            "Um preço tantas vezes acima ou abaixo da referência é produto ou unidade trocada e fica de fora."),
    ATTRIBUTION_WINDOW_DAYS("economizaai.attribution.window-days", SAVINGS, integer(1, 90).unit("dias"),
            "Economia creditada ao app: janela",
            "A compra precisa acontecer até tantos dias depois de a oferta ser mostrada."),
    ATTRIBUTION_FOLLOW_UP_DAYS("economizaai.attribution.follow-up-days", SAVINGS,
            integer(1, 365).unit("dias").app("savingsFollowUpDays"),
            "Economia creditada ao app: compras seguintes",
            "Por quantos dias as próximas compras do produto naquele mercado continuam contando."),
    CONSUMPTION_MIN_PURCHASES("economizaai.consumption.min-purchases-for-prediction", SAVINGS, integer(2, 50).unit("compras"),
            "Previsão: compras mínimas",
            "Dias de compra diferentes necessários para prever quando um produto acaba."),
    CONSUMPTION_FALLBACK_MIN_PURCHASES("economizaai.consumption.fallback-min-purchases-for-prediction", SAVINGS,
            integer(2, 50).unit("compras"),
            "Previsão: compras mínimas para casa nova",
            "Usado só quando nenhum produto alcança o mínimo, para a tela não ficar vazia."),
    CONSUMPTION_HISTORY_LOOKBACK_DAYS("economizaai.consumption.history-lookback-days", SAVINGS, integer(30, 1825).unit("dias"),
            "Previsão: histórico considerado",
            "Quantos dias para trás entram no cálculo do intervalo entre compras."),
    CONSUMPTION_RUNNING_LOW_DAYS("economizaai.consumption.running-low-threshold-days", SAVINGS,
            integer(1, 60).unit("dias").app("runningLowDays"),
            "Previsão: quando está acabando",
            "Faltando esse tanto de dias o produto passa a aparecer como acabando."),
    CONSUMPTION_RAN_OUT_GRACE_DAYS("economizaai.consumption.ran-out-grace-days", SAVINGS, integer(0, 30).unit("dias"),
            "Previsão: tolerância para acabou",
            "Dias de folga depois da data prevista antes de marcar como acabou."),
    PREFERENCES_MIN_PURCHASES("economizaai.preferences.min-purchases-per-generic", SAVINGS, integer(1, 100).unit("compras"),
            "Preferência de marca: compras mínimas",
            "Compras de um tipo de produto necessárias para concluir uma preferência."),
    PREFERENCES_MUST_HAVE_SHARE("economizaai.preferences.must-have-brand-share", SAVINGS, decimal("0.5", "1"),
            "Preferência de marca: só compra essa",
            "Fração das compras numa marca para tratá-la como indispensável (0,85 = 85%)."),
    PREFERENCES_PREFERRED_SHARE("economizaai.preferences.preferred-brand-share", SAVINGS, decimal("0.3", "1"),
            "Preferência de marca: prefere essa",
            "Fração das compras numa marca para tratá-la como preferida."),

    // ---- Notificações ----
    QUIET_HOURS_ENABLED("economizaai.notifications.quiet-hours.enabled", NOTIFICATIONS, toggle(),
            "Horário de silêncio ativo",
            "Nada é enviado por push ou e-mail dentro do horário; fica na caixa de entrada e sai depois."),
    QUIET_HOURS_START("economizaai.notifications.quiet-hours.start-hour", NOTIFICATIONS,
            integer(0, 23).unit("h").app("quietHoursStart"),
            "Silêncio: começa às",
            "Hora de Brasília em que o silêncio começa."),
    QUIET_HOURS_END("economizaai.notifications.quiet-hours.end-hour", NOTIFICATIONS,
            integer(0, 23).unit("h").app("quietHoursEnd"),
            "Silêncio: termina às",
            "Hora de Brasília em que o silêncio termina."),
    QUIET_HOURS_MAX_DEFERRED("economizaai.notifications.quiet-hours.max-deferred-per-user", NOTIFICATIONS,
            integer(0, 50).unit("avisos").app("quietHoursMaxDeferred"),
            "Silêncio: avisos enviados ao acabar",
            "Quantos avisos guardados cada pessoa recebe quando o silêncio termina; o resto fica só na caixa de entrada."),
    RELEVANCE_MODE("economizaai.notifications.relevance.mode", NOTIFICATIONS, option(relevanceModes()),
            "Filtro por feedback das ofertas",
            "Desligado não filtra. Só medir calcula sem esconder nada. Ligado esconde de fato as ofertas que a "
                    + "pessoa dispensou ou silenciou."),
    RELEVANCE_DISMISSED_DAYS("economizaai.notifications.relevance.dismissed-days", NOTIFICATIONS, integer(1, 365).unit("dias"),
            "Oferta dispensada some por",
            "Por quantos dias uma oferta dispensada deixa de aparecer."),
    RELEVANCE_MUTED_DAYS("economizaai.notifications.relevance.muted-days", NOTIFICATIONS, integer(1, 730).unit("dias"),
            "Produto silenciado some por",
            "Por quantos dias um produto silenciado deixa de aparecer."),
    REPLENISHMENT_MAX_REMINDERS("economizaai.notifications.replenishment.max-reminders", NOTIFICATIONS,
            integer(1, 20).unit("lembretes"),
            "Reposição: lembretes por compra",
            "Quantas vezes lembrar que um produto está acabando antes de parar de insistir."),
    REPLENISHMENT_SPACING_DAYS("economizaai.notifications.replenishment.reminder-spacing-days", NOTIFICATIONS,
            integer(1, 60).unit("dias"),
            "Reposição: intervalo entre lembretes",
            "Dias entre um lembrete e o próximo do mesmo produto."),
    PRICE_ALERT_COOLDOWN_HOURS("economizaai.notifications.price-alert-cooldown-hours", NOTIFICATIONS,
            integer(1, 720).unit("horas"),
            "Alerta de preço: intervalo mínimo",
            "Tempo até o mesmo alerta poder disparar de novo."),
    RECEIPT_READY_THRESHOLD_MINUTES("economizaai.notifications.receipt-ready-threshold-minutes", NOTIFICATIONS,
            integer(0, 120).unit("minutos"),
            "Avisar que a nota ficou pronta",
            "Só avisa quando o processamento demorou mais que isso."),
    SPENDING_REPORTS_ENABLED("economizaai.notifications.whatsapp.weekly-report.enabled", NOTIFICATIONS, toggle(),
            "Enviar relatório de gastos",
            "Liga o envio semanal e mensal do relatório de gastos a quem pediu."),
    WHATSAPP_AUDIENCE("economizaai.notifications.whatsapp.audience", NOTIFICATIONS, option(List.of("ADMINS", "EVERYONE")),
            "WhatsApp: quem pode usar",
            "O canal pode existir só para administradores ou para todos os usuários."),

    // ---- Custos e limites de uso ----
    PAID_API_GUARD_ENABLED("economizaai.paid-api.enabled", COSTS, toggle(),
            "Tetos de gasto ativos",
            "Desligado, nenhum teto abaixo é aplicado (as chamadas continuam sendo registradas)."),
    INFOSIMPLES_DAILY_CAP_PER_USER("economizaai.paid-api.infosimples-daily-cap-per-user", COSTS, integer(0, 10000).unit("por dia"),
            "Infosimples: consultas por usuário", "0 = sem limite."),
    CAPTCHA_DAILY_CAP_PER_USER("economizaai.paid-api.captcha-daily-cap-per-user", COSTS, integer(0, 10000).unit("por dia"),
            "Captcha: resoluções por usuário", "0 = sem limite."),
    TWILIO_DAILY_CAP_PER_USER("economizaai.paid-api.twilio-daily-cap-per-user", COSTS, integer(0, 10000).unit("por dia"),
            "SMS/WhatsApp: mensagens por usuário", "0 = sem limite."),
    LLM_VISION_DAILY_CAP_PER_USER("economizaai.paid-api.llm-vision-daily-cap-per-user", COSTS, integer(0, 10000).unit("fotos por dia"),
            "IA de leitura de foto: fotos por usuário", "Conta por foto, não por nota. 0 = sem limite."),
    LLM_HTML_RESCUE_DAILY_CAP_PER_USER("economizaai.paid-api.llm-html-rescue-daily-cap-per-user", COSTS,
            integer(0, 10000).unit("por dia"),
            "IA de resgate de nota: usos por usuário", "0 = sem limite."),
    LLM_ENRICH_DAILY_CAP_PER_USER("economizaai.paid-api.llm-enrich-daily-cap-per-user", COSTS, integer(0, 10000).unit("por dia"),
            "IA de categorização: usos por usuário", "0 = sem limite."),
    INFOSIMPLES_DAILY_BUDGET_CENTS("economizaai.paid-api.infosimples-daily-budget-cents", COSTS,
            integer(0, 10000000).unit("centavos por dia"),
            "Infosimples: teto diário", "Estourou, só esse serviço para até o dia seguinte. 0 = sem teto."),
    CAPTCHA_DAILY_BUDGET_CENTS("economizaai.paid-api.captcha-daily-budget-cents", COSTS,
            integer(0, 10000000).unit("centavos por dia"),
            "Captcha: teto diário", "0 = sem teto."),
    LLM_VISION_DAILY_BUDGET_CENTS("economizaai.paid-api.llm-vision-daily-budget-cents", COSTS,
            integer(0, 10000000).unit("centavos por dia"),
            "IA de leitura de foto: teto diário", "0 = sem teto."),
    LLM_HTML_RESCUE_DAILY_BUDGET_CENTS("economizaai.paid-api.llm-html-rescue-daily-budget-cents", COSTS,
            integer(0, 10000000).unit("centavos por dia"),
            "IA de resgate de nota: teto diário", "0 = sem teto."),
    LLM_ENRICH_DAILY_BUDGET_CENTS("economizaai.paid-api.llm-enrich-daily-budget-cents", COSTS,
            integer(0, 10000000).unit("centavos por dia"),
            "IA de categorização: teto diário", "0 = sem teto."),
    TWILIO_DAILY_BUDGET_CENTS("economizaai.paid-api.twilio-daily-budget-cents", COSTS,
            integer(0, 10000000).unit("centavos por dia"),
            "SMS/WhatsApp: teto diário", "0 = sem teto."),
    DAILY_GLOBAL_BUDGET_CENTS("economizaai.paid-api.daily-global-budget-cents", COSTS,
            integer(0, 100000000).unit("centavos por dia"),
            "Teto diário somando tudo", "Soma de todos os serviços pagos. 0 = desligado."),
    AI_DAILY_REQUEST_CAP("economizaai.ai.daily-request-cap", COSTS, integer(0, 1000000).unit("chamadas por dia"),
            "IA: chamadas por dia",
            "Teto geral de chamadas de IA no dia, além dos tetos em reais."),
    USD_BRL_RATE("economizaai.costs.usd-brl-rate", COSTS, decimal("1", "20").unit("R$ por US$"),
            "Câmbio do dólar",
            "Usado para converter o gasto de IA (cobrado em dólar) em reais no painel de custos."),
    INFOSIMPLES_LOW_BALANCE("economizaai.infosimples.low-balance-threshold", COSTS, decimal("0", "10000").unit("R$"),
            "Infosimples: avisar saldo abaixo de",
            "Abaixo desse saldo os administradores são avisados."),

    // ---- Notas e estados ----
    PAUSED_STATES("economizaai.ingestion.paused-state-probe.ufs", RECEIPTS, ufList(),
            "Estados pausados",
            "Estados com o portal da SEFAZ fora do ar: aparecem como pausados no app e são sondados até voltar."),
    DEVICE_FETCH_TRUSTED_STATES("economizaai.ingestion.sefaz.prefetch-allowed-ufs", RECEIPTS, ufList(),
            "Estados com busca pelo aparelho confiável",
            "Notas desses estados buscadas pelo celular entram no índice de preços da comunidade."),
    DEVICE_FETCH_GENERIC_ENABLED("economizaai.ingestion.device-fetch.generic-enabled", RECEIPTS, toggle(),
            "Busca pelo aparelho em qualquer estado",
            "Quando o servidor não consegue buscar a nota, o celular tenta antes de gastar com API paga. "
                    + "Fora dos estados confiáveis a nota fica só no histórico de quem enviou."),
    AUTO_CONFIRM_ENABLED("economizaai.receipts.auto-confirm.enabled", RECEIPTS, toggle(),
            "Confirmar notas pendentes sozinho",
            "Notas que a pessoa não revisou são confirmadas automaticamente."),
    AUTO_CONFIRM_AFTER_HOURS("economizaai.receipts.auto-confirm.after-hours", RECEIPTS, integer(1, 720).unit("horas"),
            "Confirmar sozinho depois de",
            "Tempo que uma nota pode ficar pendente antes de ser confirmada."),
    CONTINGENCY_WINDOW_HOURS("economizaai.ingestion.contingency-window-hours", RECEIPTS,
            integer(1, 720).unit("horas").app("contingencyWindowHours"),
            "Nota em contingência: prazo",
            "Por quanto tempo uma nota emitida em contingência continua sendo buscada antes de desistir."),
    MAX_ITEM_PHOTOS("economizaai.llm.photo-extraction.max-photos", RECEIPTS, integer(1, 6).unit("fotos").app("maxItemPhotos"),
            "Fotos por nota",
            "Quantas fotos uma nota fotografada pode ter. Cada foto é uma leitura paga."),
    HOUSEHOLD_INVITE_HOURS("economizaai.households.invite-ttl-hours", APP,
            integer(1, 720).unit("horas").app("householdInviteHours"),
            "Convite da casa: validade",
            "Por quanto tempo um código de convite funciona."),

    // ---- Aplicativo ----
    APP_MIN_VERSION("economizaai.app-config.min-version", APP, text(20),
            "Versão mínima do app",
            "Quem tem versão menor é obrigado a atualizar. 0.0.0 não bloqueia ninguém."),
    APP_ANDROID_URL("economizaai.app-config.android-url", APP, text(300),
            "Link de atualização no Android", "Em branco usa a página do app no Google Play."),
    APP_IOS_URL("economizaai.app-config.ios-url", APP, text(300),
            "Link de atualização no iPhone", "Em branco usa a página do app na App Store."),
    APP_UPDATE_MESSAGE("economizaai.app-config.message", APP, text(300),
            "Mensagem de atualização obrigatória", "Em branco usa o texto padrão do app."),
    STORE_REVIEW_MIN_RECEIPTS("economizaai.store-review.min-confirmed-receipts", APP, integer(1, 100).unit("notas"),
            "Pedir avaliação: notas confirmadas",
            "Notas confirmadas necessárias antes de pedir avaliação na loja."),
    STORE_REVIEW_FAILURE_LOOKBACK_DAYS("economizaai.store-review.failure-lookback-days", APP, integer(0, 90).unit("dias"),
            "Pedir avaliação: sem falha recente",
            "Não pede avaliação a quem teve nota com falha nesse período."),
    STORE_REVIEW_MAX_PROMPTS("economizaai.store-review.max-prompts", APP, integer(0, 20).unit("vezes"),
            "Pedir avaliação: máximo de pedidos",
            "Quantas vezes, no total, pedir avaliação à mesma pessoa."),
    STORE_REVIEW_COOLDOWN_DAYS("economizaai.store-review.cooldown-days", APP, integer(1, 730).unit("dias"),
            "Pedir avaliação: intervalo",
            "Dias entre um pedido de avaliação e o próximo."),

    // ---- Comerciantes ----
    MERCHANT_PROMOS_FEED_ENABLED("economizaai.merchant.promos-feed-enabled", MERCHANT, toggle(),
            "Mostrar promoções de comerciantes no app",
            "Liga a vitrine de promoções publicadas pelos comerciantes para os consumidores."),
    MERCHANT_FREE_UNTIL("economizaai.merchant.free-until", MERCHANT, date(),
            "Conta de comerciante grátis até",
            "Redes que reivindicam a conta até essa data usam de graça até ela."),
    MERCHANT_CLAIM_CODE_TTL_HOURS("economizaai.merchant.claim-code-ttl-hours", MERCHANT, integer(1, 720).unit("horas"),
            "Reivindicação: validade do código",
            "Por quanto tempo vale o código enviado ao e-mail da empresa."),
    MERCHANT_CLAIM_CODE_MAX_ATTEMPTS("economizaai.merchant.claim-code-max-attempts", MERCHANT, integer(1, 50).unit("tentativas"),
            "Reivindicação: tentativas de código",
            "Erros permitidos antes de exigir uma nova reivindicação."),
    MERCHANT_CLAIM_COOLDOWN_HOURS("economizaai.merchant.claim-cooldown-hours", MERCHANT, integer(0, 720).unit("horas"),
            "Reivindicação: espera para tentar de novo",
            "Tempo até a mesma pessoa poder reivindicar a mesma rede depois de uma recusa."),
    MERCHANT_IMPORT_MAX_ROWS("economizaai.merchant.import-max-rows", MERCHANT, integer(1, 10000).unit("linhas"),
            "Importação de promoções: linhas por arquivo",
            "Máximo de linhas aceitas em cada arquivo de promoções.");

    private static final Map<String, RuntimeSetting> BY_KEY =
            Arrays.stream(values()).collect(Collectors.toMap(RuntimeSetting::getKey, Function.identity()));

    private final String key;
    private final SettingSection section;
    private final SettingSpec spec;
    private final String label;
    private final String description;

    RuntimeSetting(String key, SettingSection section, SettingSpec spec, String label, String description) {
        this.key = key;
        this.section = section;
        this.spec = spec;
        this.label = label;
        this.description = description;
    }

    public static Optional<RuntimeSetting> byKey(String key) {
        return Optional.ofNullable(BY_KEY.get(key));
    }

    private static List<String> featureNames() {
        return Arrays.stream(Feature.values()).map(Feature::name).toList();
    }

    private static List<String> relevanceModes() {
        return Arrays.stream(RelevanceMode.values()).map(RelevanceMode::name).toList();
    }
}
