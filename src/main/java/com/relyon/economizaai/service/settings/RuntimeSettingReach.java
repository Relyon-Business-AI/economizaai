package com.relyon.economizaai.service.settings;

import java.util.EnumMap;
import java.util.Map;

/**
 * Where each setting shows up for the people using the product: the screens, notices and
 * routines a change reaches. Shown in the admin next to the setting, so whoever changes a
 * value knows what moves with it.
 */
public final class RuntimeSettingReach {

    private static final Map<RuntimeSetting, String> REFLECTS_ON = new EnumMap<>(RuntimeSetting.class);

    static {
        REFLECTS_ON.put(RuntimeSetting.SUBSCRIPTION_ENFORCE,
                "Todo o app: com ele ligado, quem não é Premium passa a esbarrar nos limites e vê o convite para assinar.");
        REFLECTS_ON.put(RuntimeSetting.FREE_MONTHLY_RECEIPTS,
                "Envio de notas (scanner, chave e foto) de quem está no plano grátis, o contador de uso da tela Premium e a landing.");
        REFLECTS_ON.put(RuntimeSetting.FREE_HISTORY_DAYS,
                "Gastos, histórico e gráficos da Home de quem está no plano grátis, e a landing.");
        REFLECTS_ON.put(RuntimeSetting.FREE_WATCHED_MARKETS,
                "Tela de mercados: quantos mercados o plano grátis consegue acompanhar, e a landing.");
        REFLECTS_ON.put(RuntimeSetting.FREE_HOUSEHOLD_MEMBERS,
                "Tela Casa: convites e entrada de novas pessoas quando ninguém é Premium.");
        REFLECTS_ON.put(RuntimeSetting.FREE_CUSTOM_CATEGORIES,
                "Criação de categorias próprias na casa; as já criadas continuam.");
        REFLECTS_ON.put(RuntimeSetting.FREE_OPEN_FEATURES,
                "Cada recurso listado deixa de pedir Premium em todo o app.");
        REFLECTS_ON.put(RuntimeSetting.HOUSEHOLD_MEMBERS_MAX,
                "Tela Casa: limite de convites e de entrada, inclusive para casas Premium.");
        REFLECTS_ON.put(RuntimeSetting.PAID_GRACE_DAYS,
                "Rotina que rebaixa assinaturas vencidas: quanto tempo o Premium continua após o vencimento.");
        REFLECTS_ON.put(RuntimeSetting.PREMIUM_MONTHLY_PRICE,
                "Checkout web do Mercado Pago (novas assinaturas), projeções de receita do painel e o preço mostrado na landing.");
        REFLECTS_ON.put(RuntimeSetting.PREMIUM_YEARLY_PRICE,
                "Projeções de receita do painel e o preço anual mostrado na landing. Não altera cobrança.");
        REFLECTS_ON.put(RuntimeSetting.PREMIUM_ASSUMED_LIFETIME_MONTHS,
                "Painel de aquisição do admin: o valor estimado de cada assinante e o retorno das campanhas. Não altera cobrança.");
        REFLECTS_ON.put(RuntimeSetting.REFERRAL_REWARD_MONTHS,
                "Recompensas de indicação concedidas daqui em diante, o aviso de recompensa (push e caixa de entrada) e os textos de indicação do app.");
        REFLECTS_ON.put(RuntimeSetting.LAUNCH_PROMO_ENABLED,
                "Confirmação da primeira nota: se o usuário novo ganha ou não o Premium de lançamento.");
        REFLECTS_ON.put(RuntimeSetting.LAUNCH_PROMO_UNTIL,
                "Novas concessões do Premium de lançamento, avisos de fim da promoção e a data mostrada na FAQ do app.");
        REFLECTS_ON.put(RuntimeSetting.LAUNCH_PROMO_MONTHS,
                "Duração do Premium de lançamento de quem ganhar enquanto não houver data final.");
        REFLECTS_ON.put(RuntimeSetting.LAUNCH_PROMO_WARNINGS_ENABLED,
                "Push e caixa de entrada de quem tem o Premium de lançamento sem assinatura paga.");
        REFLECTS_ON.put(RuntimeSetting.LAUNCH_PROMO_WARNING_DAYS,
                "Datas em que os avisos de fim da promoção são enviados e o texto do app que explica esses avisos.");
        REFLECTS_ON.put(RuntimeSetting.PRICE_WINDOW_DAYS,
                "Plano de compras, melhores mercados, preço típico, mapa de preços, promoções e a tela de preços de um mercado (período máximo).");
        REFLECTS_ON.put(RuntimeSetting.MARKET_PRICES_DEFAULT_DAYS,
                "Tela de preços de um mercado, no app: período selecionado ao abrir.");
        REFLECTS_ON.put(RuntimeSetting.DEALS_MAX_PRICE_AGE_DAYS,
                "Tela \"Ofertas pra você\" e o resumo diário de ofertas por push.");
        REFLECTS_ON.put(RuntimeSetting.NEARBY_RADIUS_KM,
                "Melhores mercados, ofertas, promoções da comunidade e a ordem da busca de produtos.");
        REFLECTS_ON.put(RuntimeSetting.COMMUNITY_PROMO_THRESHOLD_PCT,
                "Lista de promoções da comunidade e os selos de promoção nos preços.");
        REFLECTS_ON.put(RuntimeSetting.COMMUNITY_PROMO_RECENT_WINDOW_DAYS,
                "Detecção de promoções da comunidade: separa preço recente de preço típico.");
        REFLECTS_ON.put(RuntimeSetting.COMMUNITY_PROMO_MIN_BASELINE,
                "Detecção de promoções da comunidade: produtos com pouco histórico ficam de fora.");
        REFLECTS_ON.put(RuntimeSetting.COMMUNITY_PROMO_MIN_OLD,
                "Detecção de promoções da comunidade: sem preços antigos suficientes não há preço típico para comparar.");
        REFLECTS_ON.put(RuntimeSetting.COMMUNITY_PROMO_MIN_RECENT,
                "Detecção de promoções da comunidade: evita promoção criada por uma única nota.");
        REFLECTS_ON.put(RuntimeSetting.MIN_OBSERVATIONS_PER_PRODUCT_MARKET,
                "Preços de referência em produtos e lojas sensíveis (saúde, farmácia) e nas listagens do admin e do lojista.");
        REFLECTS_ON.put(RuntimeSetting.PERSONAL_PROMO_THRESHOLD_PCT,
                "Selo de promoção pessoal na revisão da nota e nas ofertas.");
        REFLECTS_ON.put(RuntimeSetting.PERSONAL_PROMO_MIN_PURCHASES,
                "Selo de promoção pessoal: produtos comprados poucas vezes ficam de fora.");
        REFLECTS_ON.put(RuntimeSetting.DEAL_MAX_DROP_PCT,
                "Tela \"Ofertas pra você\", alertas de mercado mais barato e resumo de ofertas, para itens baratos.");
        REFLECTS_ON.put(RuntimeSetting.DEAL_MIN_DROP_PCT,
                "Tela \"Ofertas pra você\", alertas de mercado mais barato e resumo de ofertas, para itens caros.");
        REFLECTS_ON.put(RuntimeSetting.DEAL_LOW_PRICE_ANCHOR,
                "Mesma regra de oferta relevante: define o que conta como item barato.");
        REFLECTS_ON.put(RuntimeSetting.DEAL_HIGH_PRICE_ANCHOR,
                "Mesma regra de oferta relevante: define o que conta como item caro.");
        REFLECTS_ON.put(RuntimeSetting.COMPARISON_MIN_MATCHED_PCT,
                "\"Esta compra em outros mercados\" na tela da nota e as alternativas guardadas em cada ida às compras.");
        REFLECTS_ON.put(RuntimeSetting.RECEIPT_COMPARISON_MAX_AGE_DAYS,
                "\"Esta compra em outros mercados\" na tela da nota: notas mais antigas não mostram comparação.");
        REFLECTS_ON.put(RuntimeSetting.COMPARISON_ALTERNATIVE_MARKETS,
                "\"Esta compra em outros mercados\" na tela da nota (comparações novas) e o registro de cada ida às compras.");
        REFLECTS_ON.put(RuntimeSetting.ROUTE_DEFAULT_MAX_DISTANCE_KM,
                "Plano de compras, no app: distância máxima de quem ainda não escolheu a sua.");
        REFLECTS_ON.put(RuntimeSetting.ROUTE_DEFAULT_MAX_STOPS,
                "Plano de compras, no app: número de paradas de quem ainda não escolheu o seu.");
        REFLECTS_ON.put(RuntimeSetting.ROUTE_DEFAULT_MIN_SAVING_PER_STOP,
                "Plano de compras, no app: economia mínima por parada de quem ainda não escolheu a sua.");
        REFLECTS_ON.put(RuntimeSetting.SAVINGS_DEFAULT_PERIOD_DAYS,
                "Economia da casa, no app, quando nenhum período é escolhido.");
        REFLECTS_ON.put(RuntimeSetting.HOME_SAVINGS_MIN,
                "Tela inicial do app: o destaque de economia só aparece a partir desse valor.");
        REFLECTS_ON.put(RuntimeSetting.LEADERBOARD_TOP_N,
                "Ranking de caçadores de desconto, no app e no admin, e os textos do app que citam o tamanho do ranking.");
        REFLECTS_ON.put(RuntimeSetting.LEADERBOARD_DEFAULT_DAYS,
                "Ranking de caçadores de desconto, no app e no admin, quando nenhum período é escolhido.");
        REFLECTS_ON.put(RuntimeSetting.SAVINGS_REGION_WINDOW_DAYS,
                "Tela Economia: seção \"Comparado à sua região\".");
        REFLECTS_ON.put(RuntimeSetting.SAVINGS_MIN_REGION_SAMPLES,
                "Tela Economia: decide entre comparar com a região ou com o histórico da casa.");
        REFLECTS_ON.put(RuntimeSetting.SAVINGS_OWN_LOOKBACK_DAYS,
                "Tela Economia: itens comparados com as compras anteriores da própria casa.");
        REFLECTS_ON.put(RuntimeSetting.SAVINGS_MIN_OWN_SAMPLES,
                "Tela Economia: itens comprados poucas vezes ficam sem comparação.");
        REFLECTS_ON.put(RuntimeSetting.SAVINGS_MAX_PRICE_RATIO,
                "Tela Economia: linhas com diferença absurda são descartadas do total.");
        REFLECTS_ON.put(RuntimeSetting.ATTRIBUTION_WINDOW_DAYS,
                "Total \"você economizou com as dicas\" e a métrica de economia gerada no admin.");
        REFLECTS_ON.put(RuntimeSetting.ATTRIBUTION_FOLLOW_UP_DAYS,
                "Total \"você economizou com as dicas\" e o texto explicativo da tela Economia.");
        REFLECTS_ON.put(RuntimeSetting.CONSUMPTION_MIN_PURCHASES,
                "Tela Previsão de consumo e lembretes de reposição: quais produtos têm previsão.");
        REFLECTS_ON.put(RuntimeSetting.CONSUMPTION_FALLBACK_MIN_PURCHASES,
                "Tela Previsão de consumo de casas com pouco histórico.");
        REFLECTS_ON.put(RuntimeSetting.CONSUMPTION_HISTORY_LOOKBACK_DAYS,
                "Cálculo do intervalo entre compras usado na previsão de consumo.");
        REFLECTS_ON.put(RuntimeSetting.CONSUMPTION_RUNNING_LOW_DAYS,
                "Status \"acabando\" na Previsão de consumo e o disparo do lembrete de reposição.");
        REFLECTS_ON.put(RuntimeSetting.CONSUMPTION_RAN_OUT_GRACE_DAYS,
                "Status \"acabou\" na Previsão de consumo.");
        REFLECTS_ON.put(RuntimeSetting.PREFERENCES_MIN_PURCHASES,
                "Preferências de marca calculadas para a casa: quais tipos de produto entram.");
        REFLECTS_ON.put(RuntimeSetting.PREFERENCES_MUST_HAVE_SHARE,
                "Preferências de marca da casa: quando uma marca é marcada como indispensável.");
        REFLECTS_ON.put(RuntimeSetting.PREFERENCES_PREFERRED_SHARE,
                "Preferências de marca da casa: quando uma marca é marcada como preferida.");
        REFLECTS_ON.put(RuntimeSetting.QUIET_HOURS_ENABLED,
                "Todos os pushes e e-mails, menos os avisos sobre a nota enviada.");
        REFLECTS_ON.put(RuntimeSetting.QUIET_HOURS_START,
                "Envio de pushes e e-mails e o horário mostrado na FAQ do app.");
        REFLECTS_ON.put(RuntimeSetting.QUIET_HOURS_END,
                "Envio de pushes e e-mails e o horário mostrado na FAQ do app.");
        REFLECTS_ON.put(RuntimeSetting.QUIET_HOURS_MAX_DEFERRED,
                "Pushes enviados logo após o silêncio e o número citado na FAQ do app.");
        REFLECTS_ON.put(RuntimeSetting.RELEVANCE_MODE,
                "Tela \"Ofertas pra você\" e resumo de ofertas; em \"Só medir\" só alimenta o relatório de relevância do admin.");
        REFLECTS_ON.put(RuntimeSetting.RELEVANCE_DISMISSED_DAYS,
                "Ofertas dispensadas pelo usuário, quando o filtro está ligado.");
        REFLECTS_ON.put(RuntimeSetting.RELEVANCE_MUTED_DAYS,
                "Produtos silenciados pelo usuário, quando o filtro está ligado.");
        REFLECTS_ON.put(RuntimeSetting.REPLENISHMENT_MAX_REMINDERS,
                "Notificação \"está acabando\": quantas vezes insiste por compra.");
        REFLECTS_ON.put(RuntimeSetting.REPLENISHMENT_SPACING_DAYS,
                "Notificação \"está acabando\": espaço entre um lembrete e outro.");
        REFLECTS_ON.put(RuntimeSetting.PRICE_ALERT_COOLDOWN_HOURS,
                "Alertas de preço e regras de notificação criadas pelo usuário.");
        REFLECTS_ON.put(RuntimeSetting.RECEIPT_READY_THRESHOLD_MINUTES,
                "Push \"sua nota ficou pronta\" depois do envio de uma nota.");
        REFLECTS_ON.put(RuntimeSetting.SPENDING_REPORTS_ENABLED,
                "Relatório de gastos semanal e mensal, por e-mail e WhatsApp.");
        REFLECTS_ON.put(RuntimeSetting.WHATSAPP_AUDIENCE,
                "Opção de WhatsApp nas preferências de notificação e o envio por esse canal (tem custo por mensagem).");
        REFLECTS_ON.put(RuntimeSetting.PAID_API_GUARD_ENABLED,
                "Todas as consultas pagas: Infosimples, captcha, IA e SMS/WhatsApp.");
        REFLECTS_ON.put(RuntimeSetting.INFOSIMPLES_DAILY_CAP_PER_USER,
                "Busca de nota pelo caminho pago; ao atingir, o usuário vê a nota como pendente até o dia seguinte.");
        REFLECTS_ON.put(RuntimeSetting.CAPTCHA_DAILY_CAP_PER_USER,
                "Notas de estados com captcha (MS, SC, SE, MG e outros).");
        REFLECTS_ON.put(RuntimeSetting.TWILIO_DAILY_CAP_PER_USER,
                "Códigos por SMS e avisos por WhatsApp.");
        REFLECTS_ON.put(RuntimeSetting.LLM_VISION_DAILY_CAP_PER_USER,
                "Envio de nota por foto.");
        REFLECTS_ON.put(RuntimeSetting.LLM_HTML_RESCUE_DAILY_CAP_PER_USER,
                "Recuperação por IA de notas que o leitor normal não entendeu.");
        REFLECTS_ON.put(RuntimeSetting.LLM_ENRICH_DAILY_CAP_PER_USER,
                "Sugestão de categoria por IA na nota e em \"corrigir item\".");
        REFLECTS_ON.put(RuntimeSetting.INFOSIMPLES_DAILY_BUDGET_CENTS,
                "Caminho pago de busca de nota para todos os usuários; alerta no WhatsApp em 80% e 100%.");
        REFLECTS_ON.put(RuntimeSetting.CAPTCHA_DAILY_BUDGET_CENTS,
                "Notas de estados com captcha para todos os usuários; alerta em 80% e 100%.");
        REFLECTS_ON.put(RuntimeSetting.LLM_VISION_DAILY_BUDGET_CENTS,
                "Nota por foto para todos os usuários; alerta em 80% e 100%.");
        REFLECTS_ON.put(RuntimeSetting.LLM_HTML_RESCUE_DAILY_BUDGET_CENTS,
                "Recuperação de notas por IA para todos os usuários; alerta em 80% e 100%.");
        REFLECTS_ON.put(RuntimeSetting.LLM_ENRICH_DAILY_BUDGET_CENTS,
                "Categorização por IA para todos os usuários; alerta em 80% e 100%.");
        REFLECTS_ON.put(RuntimeSetting.TWILIO_DAILY_BUDGET_CENTS,
                "SMS e WhatsApp para todos os usuários; alerta em 80% e 100%.");
        REFLECTS_ON.put(RuntimeSetting.DAILY_GLOBAL_BUDGET_CENTS,
                "Todos os serviços pagos de uma vez quando a soma do dia estoura.");
        REFLECTS_ON.put(RuntimeSetting.AI_DAILY_REQUEST_CAP,
                "Todas as chamadas de IA: categorização, curadoria e leitura de foto.");
        REFLECTS_ON.put(RuntimeSetting.USD_BRL_RATE,
                "Admin → Operações → Custos: valores de IA em reais. Não altera cobrança.");
        REFLECTS_ON.put(RuntimeSetting.INFOSIMPLES_LOW_BALANCE,
                "Alerta de saldo baixo por e-mail e WhatsApp aos administradores.");
        REFLECTS_ON.put(RuntimeSetting.PAUSED_STATES,
                "FAQ \"situação dos estados\" no app, sonda de retorno do portal e alertas do Infosimples.");
        REFLECTS_ON.put(RuntimeSetting.DEVICE_FETCH_TRUSTED_STATES,
                "Notas buscadas pelo celular: se entram ou não nos preços da comunidade.");
        REFLECTS_ON.put(RuntimeSetting.DEVICE_FETCH_GENERIC_ENABLED,
                "Envio de nota quando o portal bloqueia o servidor: o app tenta buscar antes do caminho pago.");
        REFLECTS_ON.put(RuntimeSetting.AUTO_CONFIRM_ENABLED,
                "Notas pendentes de revisão: entram sozinhas nos gastos e nos preços da comunidade.");
        REFLECTS_ON.put(RuntimeSetting.AUTO_CONFIRM_AFTER_HOURS,
                "Tempo que o usuário tem para revisar antes da confirmação automática.");
        REFLECTS_ON.put(RuntimeSetting.CONTINGENCY_WINDOW_HOURS,
                "Notas emitidas sem internet: por quanto tempo seguem sendo buscadas, e o prazo citado no app.");
        REFLECTS_ON.put(RuntimeSetting.MAX_ITEM_PHOTOS,
                "Envio de nota por foto no scanner e o texto \"até N fotos\" do app.");
        REFLECTS_ON.put(RuntimeSetting.DATA_SHARE_CONSENT_DAYS,
                "Pedidos de cópia de dados feitos daqui em diante ao sair de uma casa, e o prazo mostrado no app.");
        REFLECTS_ON.put(RuntimeSetting.HOUSEHOLD_INVITE_HOURS,
                "Códigos de convite gerados a partir de agora e o prazo citado na tela Casa e na FAQ.");
        REFLECTS_ON.put(RuntimeSetting.APP_MIN_VERSION,
                "Tela de atualização obrigatória ao abrir o app, em todos os celulares com versão menor.");
        REFLECTS_ON.put(RuntimeSetting.APP_ANDROID_URL,
                "Botão \"Atualizar\" da tela de atualização obrigatória no Android.");
        REFLECTS_ON.put(RuntimeSetting.APP_IOS_URL,
                "Botão \"Atualizar\" da tela de atualização obrigatória no iPhone.");
        REFLECTS_ON.put(RuntimeSetting.APP_UPDATE_MESSAGE,
                "Texto da tela de atualização obrigatória.");
        REFLECTS_ON.put(RuntimeSetting.STORE_REVIEW_MIN_RECEIPTS,
                "Pedido de avaliação na App Store e no Google Play.");
        REFLECTS_ON.put(RuntimeSetting.STORE_REVIEW_FAILURE_LOOKBACK_DAYS,
                "Pedido de avaliação: evita pedir a quem acabou de ter problema.");
        REFLECTS_ON.put(RuntimeSetting.STORE_REVIEW_MAX_PROMPTS,
                "Pedido de avaliação: total de vezes por pessoa.");
        REFLECTS_ON.put(RuntimeSetting.STORE_REVIEW_COOLDOWN_DAYS,
                "Pedido de avaliação: tempo entre um pedido e outro.");
        REFLECTS_ON.put(RuntimeSetting.MERCHANT_PROMOS_FEED_ENABLED,
                "App do consumidor: vitrine de promoções patrocinadas.");
        REFLECTS_ON.put(RuntimeSetting.MERCHANT_FREE_UNTIL,
                "Portal do comerciante: data de gratuidade mostrada e aplicada à conta.");
        REFLECTS_ON.put(RuntimeSetting.MERCHANT_CLAIM_CODE_TTL_HOURS,
                "Portal do comerciante: fluxo \"sou este mercado\".");
        REFLECTS_ON.put(RuntimeSetting.MERCHANT_CLAIM_CODE_MAX_ATTEMPTS,
                "Portal do comerciante: fluxo \"sou este mercado\".");
        REFLECTS_ON.put(RuntimeSetting.MERCHANT_CLAIM_COOLDOWN_HOURS,
                "Portal do comerciante: nova tentativa de reivindicar a mesma rede.");
        REFLECTS_ON.put(RuntimeSetting.MERCHANT_VERIFIED_BADGE_TOLERANCE_PCT,
                "Selo \"confirmado por notas\" nas promoções de comerciantes (app, painel do comerciante e admin) e o desempenho da promoção.");
        REFLECTS_ON.put(RuntimeSetting.MERCHANT_IMPORT_MAX_ROWS,
                "Portal do comerciante: importação de promoções por arquivo.");
    }

    private RuntimeSettingReach() {}

    public static String of(RuntimeSetting setting) {
        return REFLECTS_ON.get(setting);
    }
}
