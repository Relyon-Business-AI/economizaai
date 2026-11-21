package com.relyon.economizaai.service.settings;

import lombok.Getter;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static com.relyon.economizaai.service.settings.RuntimeSetting.*;

/**
 * Settings that only make sense together: one rule spread over several fields. The admin draws
 * each group as one block, with the title and the explanation of how the fields combine.
 */
@Getter
public enum SettingGroup {

    FREE_PLAN("Limites do plano grátis",
            "Só valem com \"Cobrar pelo Premium\" ligado. Cada limite é independente dos outros; um recurso marcado "
                    + "em \"Recursos Premium liberados no grátis\" fica aberto a todos e ignora o limite correspondente.",
            SUBSCRIPTION_ENFORCE, FREE_MONTHLY_RECEIPTS, FREE_HISTORY_DAYS, FREE_WATCHED_MARKETS,
            FREE_HOUSEHOLD_MEMBERS, FREE_CUSTOM_CATEGORIES, FREE_OPEN_FEATURES),
    PREMIUM_REVENUE("Preço do Premium e projeção de receita",
            "O preço mensal é o cobrado na web. Preço anual e meses de assinatura só alimentam as projeções do painel: "
                    + "receita estimada por assinante = preço mensal × meses de assinatura.",
            PREMIUM_MONTHLY_PRICE, PREMIUM_YEARLY_PRICE, PREMIUM_ASSUMED_LIFETIME_MONTHS),
    LAUNCH_PROMO("Promoção de lançamento",
            "Quem confirma a primeira nota ganha Premium grátis até a data definida. Se a data ficar vazia, vale a "
                    + "duração em meses. Os avisos de fim da promoção saem nos dias listados antes do término e só se "
                    + "estiverem ligados.",
            LAUNCH_PROMO_ENABLED, LAUNCH_PROMO_UNTIL, LAUNCH_PROMO_MONTHS, LAUNCH_PROMO_WARNINGS_ENABLED,
            LAUNCH_PROMO_WARNING_DAYS),
    COMMUNITY_PROMO("Como uma promoção da comunidade é detectada",
            "Para cada produto em cada mercado, os preços dentro da \"Validade dos preços da comunidade\" são divididos "
                    + "em dois grupos: os recentes (últimos dias) e os antigos (o resto), que formam o preço típico. "
                    + "Vira promoção quando há preços suficientes nos dois grupos e o preço recente está abaixo do "
                    + "típico pela queda mínima. Cuidado: o período recente precisa ser bem menor que a validade, "
                    + "senão não sobra preço antigo.",
            COMMUNITY_PROMO_RECENT_WINDOW_DAYS, COMMUNITY_PROMO_MIN_RECENT, COMMUNITY_PROMO_MIN_OLD,
            COMMUNITY_PROMO_THRESHOLD_PCT),
    PERSONAL_PROMO("Como uma promoção pessoal é detectada",
            "Compara o preço pago com o que a própria casa costuma pagar pelo produto. Só existe comparação depois "
                    + "do número mínimo de compras anteriores.",
            PERSONAL_PROMO_THRESHOLD_PCT, PERSONAL_PROMO_MIN_PURCHASES),
    DEAL_RELEVANCE("Desconto mínimo para um preço virar oferta",
            "O desconto exigido depende do preço do produto: itens no preço baixo de referência (ou abaixo) precisam "
                    + "do desconto maior; itens no preço alto de referência (ou acima), do desconto menor; entre os "
                    + "dois, o desconto exigido diminui aos poucos. O desconto maior deve ser maior que o menor, e o "
                    + "preço baixo, menor que o preço alto.",
            DEAL_MAX_DROP_PCT, DEAL_MIN_DROP_PCT, DEAL_LOW_PRICE_ANCHOR, DEAL_HIGH_PRICE_ANCHOR),
    ONLINE_OFFERS("Ofertas online",
            "A economia mínima só importa com as ofertas online ligadas.",
            ONLINE_OFFERS_ENABLED, ONLINE_OFFER_MIN_SAVINGS),
    PURCHASE_COMPARISON("\"Essa mesma compra em outros mercados\"",
            "A comparação aparece para notas recentes, lista no máximo o número de mercados definido e só inclui um "
                    + "mercado que tenha preço para a parte mínima dos itens da compra.",
            COMPARISON_MIN_MATCHED_PCT, RECEIPT_COMPARISON_MAX_AGE_DAYS, COMPARISON_ALTERNATIVE_MARKETS),
    ROUTE_DEFAULTS("Plano de compras: como ele abre",
            "Valores com que o plano de compras começa, até a pessoa escolher os dela. A distância nunca passa do "
                    + "raio de \"perto de você\".",
            ROUTE_DEFAULT_MAX_DISTANCE_KM, ROUTE_DEFAULT_MAX_STOPS, ROUTE_DEFAULT_MIN_SAVING_PER_STOP),
    PRICE_INFLATION("Preços e inflação",
            "A tela compara a inflação oficial de alimentos (IBGE) com a variação dos preços pagos nos mercados, "
                    + "segundo as notas do app. Do lado do app, só entram produtos das categorias de alimento, e cada "
                    + "comparação é o mesmo produto no mesmo mercado em dois meses seguidos; variações acima do limite "
                    + "são descartadas. Um mês só vale com o mínimo de comparações e de mercados; se faltarem meses, a "
                    + "tela mostra o trecho mais recente que tem dados e compara os dois lados nesse mesmo trecho. "
                    + "Categorias, alimentos e \"Sua cesta\" têm seus próprios mínimos. Quem vê a tela é quem recebe o "
                    + "aviso mensal, se ele estiver ligado.",
            PRICE_INFLATION_AUDIENCE, PRICE_INFLATION_PERIODS, PRICE_INFLATION_FOOD_CATEGORIES,
            PRICE_INFLATION_MIN_PAIRS_PER_MONTH, PRICE_INFLATION_MIN_STORES_PER_MONTH,
            PRICE_INFLATION_MIN_PAIRS_PER_ITEM, PRICE_INFLATION_MAX_MONTHLY_CHANGE_PERCENT,
            PRICE_INFLATION_BASKET_MIN_PRODUCTS, PRICE_INFLATION_MONTHLY_NOTICE),
    SAVINGS_REFERENCE("Quanto a casa economizou: com o que cada compra é comparada",
            "Primeiro tenta o preço típico da região: preços de outras casas perto, na janela de dias antes e depois "
                    + "da compra, desde que haja o mínimo de preços. Sem isso, usa o histórico da própria casa, dentro "
                    + "do período e com o mínimo de compras. Compras com preço muito acima da referência são "
                    + "descartadas pelo limite de diferença.",
            SAVINGS_REGION_WINDOW_DAYS, SAVINGS_MIN_REGION_SAMPLES, SAVINGS_OWN_LOOKBACK_DAYS,
            SAVINGS_MIN_OWN_SAMPLES, SAVINGS_MAX_PRICE_RATIO),
    LEADERBOARD("Ranking de caçadores de desconto",
            "Quantas casas aparecem e quantos dias o ranking cobre quando ninguém escolhe um período.",
            LEADERBOARD_TOP_N, LEADERBOARD_DEFAULT_DAYS),
    ATTRIBUTION("Economia atribuída a uma oferta",
            "Uma compra conta como economia gerada pelo app se acontece dentro da janela depois de a oferta ser "
                    + "vista. As compras seguintes do mesmo produto no mesmo mercado continuam contando pelo período "
                    + "de continuidade.",
            ATTRIBUTION_WINDOW_DAYS, ATTRIBUTION_FOLLOW_UP_DAYS),
    CONSUMPTION("Previsão de consumo (\"está acabando\")",
            "O app prevê quando um produto acaba pelo intervalo entre as compras dentro do período de histórico. "
                    + "A previsão só existe a partir do mínimo de compras; o mínimo para casa nova só é usado "
                    + "quando nenhum produto da casa alcança o mínimo normal. A confiança mostrada depende do número de compras: o mínimo para \"alta\" deve ser "
                    + "maior que o de \"média\". O produto entra em \"está acabando\" na antecedência definida e sai "
                    + "da lista depois da tolerância.",
            CONSUMPTION_MIN_PURCHASES, CONSUMPTION_FALLBACK_MIN_PURCHASES, CONSUMPTION_HISTORY_LOOKBACK_DAYS,
            CONSUMPTION_RUNNING_LOW_DAYS, CONSUMPTION_RAN_OUT_GRACE_DAYS, CONSUMPTION_HIGH_CONFIDENCE_SAMPLES,
            CONSUMPTION_MEDIUM_CONFIDENCE_SAMPLES),
    BRAND_PREFERENCE("Marca preferida",
            "Depois do mínimo de compras de um tipo de produto, o app olha a participação da marca mais comprada: "
                    + "acima da primeira faixa é \"só compra essa\"; acima da segunda, \"prefere\". A primeira faixa "
                    + "deve ser maior que a segunda, e o mínimo para confiança \"alta\", maior que o de \"média\".",
            PREFERENCES_MIN_PURCHASES, PREFERENCES_MUST_HAVE_SHARE, PREFERENCES_PREFERRED_SHARE,
            PREFERENCES_HIGH_CONFIDENCE_PURCHASES, PREFERENCES_MEDIUM_CONFIDENCE_PURCHASES),
    QUIET_HOURS("Horário de silêncio",
            "Entre a hora de início e a de fim (pode atravessar a meia-noite) nenhum aviso é enviado. Quando o "
                    + "silêncio acaba, cada pessoa recebe no máximo o número definido de avisos guardados. Desligado, "
                    + "os outros três campos não têm efeito.",
            QUIET_HOURS_ENABLED, QUIET_HOURS_START, QUIET_HOURS_END, QUIET_HOURS_MAX_DEFERRED),
    DEAL_FEEDBACK("Filtro de ofertas pelo que a pessoa dispensou",
            "Os prazos de \"dispensada\" e \"silenciada\" só escondem ofertas com o filtro em \"Ligado\". Em \"Só medir\" "
                    + "o app apenas registra o que teria escondido.",
            RELEVANCE_MODE, RELEVANCE_DISMISSED_DAYS, RELEVANCE_MUTED_DAYS),
    REPLENISHMENT("Lembretes de reposição",
            "Saem uma vez por dia, na hora definida. Para a mesma compra, o app manda no máximo o número de "
                    + "lembretes definido, com o espaçamento entre eles.",
            REPLENISHMENT_SEND_HOUR, REPLENISHMENT_MAX_REMINDERS, REPLENISHMENT_SPACING_DAYS),
    SPENDING_REPORTS("Relatório de gastos",
            "Dia e hora do relatório semanal e do mensal. Com o envio desligado, os quatro campos de dia e hora "
                    + "não têm efeito.",
            SPENDING_REPORTS_ENABLED, SPENDING_REPORT_WEEKLY_DAY, SPENDING_REPORT_WEEKLY_HOUR,
            SPENDING_REPORT_MONTHLY_DAY, SPENDING_REPORT_MONTHLY_HOUR),
    DEALS_DIGEST("Resumo de ofertas",
            "Quem escolheu resumo semanal recebe no dia definido. A hora segue o horário em que a casa costuma "
                    + "comprar, quando ela já tem o mínimo de notas; sem isso, é uma hora fixa sorteada dentro da faixa. "
                    + "Uma oferta já avisada só volta ao resumo se melhorar pelo menos o número de pontos definido.",
            DEALS_DIGEST_WEEKLY_DAY, DEALS_DIGEST_DEFAULT_HOUR_FROM, DEALS_DIGEST_DEFAULT_HOUR_TO,
            DEALS_DIGEST_MIN_RECEIPTS_FOR_HABIT, DEALS_DIGEST_IMPROVEMENT_POINTS),
    PUSH_INVITE("Convite para ativar as notificações",
            "Sai no dia e na hora definidos, para quem ainda não ativou. A mesma pessoa só é convidada de novo "
                    + "depois do intervalo.",
            PUSH_OPTIN_NUDGE_WEEKDAY, PUSH_OPTIN_NUDGE_HOUR, PUSH_OPTIN_RENUDGE_DAYS),
    USER_DAILY_CAPS("Teto por pessoa, por dia, de cada serviço pago",
            "Quantas vezes por dia uma mesma pessoa pode acionar cada serviço pago. Só valem com \"Tetos de gasto "
                    + "ativos\" ligado.",
            INFOSIMPLES_DAILY_CAP_PER_USER, CAPTCHA_DAILY_CAP_PER_USER,
            TWILIO_DAILY_CAP_PER_USER, LLM_VISION_DAILY_CAP_PER_USER, LLM_HTML_RESCUE_DAILY_CAP_PER_USER,
            LLM_ENRICH_DAILY_CAP_PER_USER),
    DAILY_BUDGETS("Orçamento diário de cada serviço pago",
            "Só valem com \"Tetos de gasto ativos\" ligado. Gasto do dia = número de chamadas × custo por chamada "
                    + "(bloco \"Custo de cada chamada paga\"). Ao atingir o orçamento, o serviço "
                    + "para até o dia seguinte. O teto geral soma todos os serviços e para tudo; em 0, fica desligado. "
                    + "O aviso sai quando o gasto chega ao percentual de aviso e de novo em 100%.",
            INFOSIMPLES_DAILY_BUDGET_CENTS, CAPTCHA_DAILY_BUDGET_CENTS, LLM_VISION_DAILY_BUDGET_CENTS,
            LLM_HTML_RESCUE_DAILY_BUDGET_CENTS, LLM_ENRICH_DAILY_BUDGET_CENTS, TWILIO_DAILY_BUDGET_CENTS,
            DAILY_GLOBAL_BUDGET_CENTS, COST_ALERT_WARNING_PERCENT),
    CALL_COSTS("Custo de cada chamada paga",
            "Quanto o app considera que cada chamada custa. Serve para somar o gasto do dia, que é comparado com os "
                    + "orçamentos acima: se o custo estiver abaixo do real, o orçamento estoura sem o app perceber. "
                    + "Vale para chamadas novas; o que já foi registrado não muda.",
            INFOSIMPLES_CALL_COST_CENTS, CAPTCHA_CALL_COST_CENTS, TWILIO_CALL_COST_CENTS,
            LLM_VISION_CALL_COST_CENTS, LLM_HTML_RESCUE_CALL_COST_CENTS, LLM_ENRICH_CALL_COST_CENTS),
    AI_SPEND("Gasto com IA",
            "O gasto estimado em dólar sai da tabela de preços dos modelos e é convertido pelo câmbio. O crédito de "
                    + "IA menos esse gasto é o saldo mostrado no painel. O teto diário de chamadas é independente do "
                    + "dinheiro.",
            AI_MODEL_PRICES, USD_BRL_RATE, AI_CREDIT_USD, AI_DAILY_REQUEST_CAP),
    AUTO_CONFIRM("Confirmação automática de notas",
            "Uma nota pendente é confirmada sozinha depois do número de horas, só com a confirmação automática ligada.",
            AUTO_CONFIRM_ENABLED, AUTO_CONFIRM_AFTER_HOURS),
    HOURLY_QUOTAS("Limites por pessoa, por hora",
            "Cada limite é independente. Ao mudar um limite, a contagem da hora de cada pessoa recomeça do zero.",
            RECEIPT_SUBMITS_PER_HOUR, RECEIPT_IMPORTS_PER_HOUR, IMPORT_PREVIEWS_PER_HOUR, HISTORY_EXPORTS_PER_HOUR),
    STATE_UNSTABLE("Quando um estado aparece como instável",
            "Um estado fica \"instável\" no app quando tem pelo menos o número de falhas dentro da janela de horas.",
            STATE_UNSTABLE_WINDOW_HOURS, STATE_UNSTABLE_MIN_FAILURES),
    PORTAL_REGRESSION("Alerta de portal que parou de funcionar",
            "Você é avisado quando um estado já validado acumula o número de falhas de leitura dentro da janela.",
            PORTAL_REGRESSION_MIN_FAILURES, PORTAL_REGRESSION_WINDOW_HOURS),
    STATE_EVIDENCE("Quando o sistema desiste de um estado ainda em teste",
            "O sistema para de gastar com um estado em validação quando as três condições são atendidas ao mesmo "
                    + "tempo: número de falhas, vindas de pessoas diferentes, em dias diferentes.",
            STATE_EVIDENCE_USER_DAYS, STATE_EVIDENCE_USERS, STATE_EVIDENCE_DAYS),
    AI_CONFIDENCE("Confiança mínima da IA para categorizar",
            "Quanto mais a IA mexe, mais certeza é exigida: preencher uma subcategoria vazia pede a menor confiança; "
                    + "trocar uma subcategoria existente, mais; trocar a categoria, a maior. Mantenha os três valores "
                    + "nessa ordem crescente.",
            AI_SET_MIN_CONFIDENCE, AI_CHANGE_SUBCATEGORY_MIN_CONFIDENCE, AI_CHANGE_CATEGORY_MIN_CONFIDENCE),
    AUTO_PROMOTION("Aprendizado automático de categorias",
            "Um padrão vira regra sozinho quando aparece em produtos suficientes e a maioria deles concorda com a "
                    + "mesma categoria.",
            AUTO_PROMOTE_MIN_SAMPLES, AUTO_PROMOTE_MIN_AGREEMENT),
    CONSENSUS("Correções feitas pelos usuários",
            "Uma correção de categoria passa a valer para todos quando casas suficientes fizeram a mesma correção, "
                    + "em produtos suficientes.",
            CONSENSUS_MIN_HOUSEHOLDS, CONSENSUS_MIN_TOKEN_PRODUCTS),
    FORCE_UPDATE("Atualização obrigatória do app",
            "Quem tem versão abaixo da mínima vê a mensagem e é mandado para a loja pelo link do seu sistema. "
                    + "Com a versão mínima em 0.0.0, ninguém é bloqueado.",
            APP_MIN_VERSION, APP_ANDROID_URL, APP_IOS_URL, APP_UPDATE_MESSAGE),
    STORE_REVIEW("Pedido de avaliação na loja",
            "O app só pede avaliação a quem já confirmou o mínimo de notas e não teve falha de nota nos últimos "
                    + "dias. Cada pessoa recebe no máximo o número de pedidos definido, com o intervalo entre eles.",
            STORE_REVIEW_MIN_RECEIPTS, STORE_REVIEW_FAILURE_LOOKBACK_DAYS, STORE_REVIEW_MAX_PROMPTS,
            STORE_REVIEW_COOLDOWN_DAYS),
    MERCHANT_CLAIM("Código de confirmação do lojista",
            "O código vale pelo número de horas e aceita o número de tentativas erradas. Estourando as tentativas, o "
                    + "lojista espera o intervalo para pedir outro.",
            MERCHANT_CLAIM_CODE_TTL_HOURS, MERCHANT_CLAIM_CODE_MAX_ATTEMPTS, MERCHANT_CLAIM_COOLDOWN_HOURS);

    private static final Map<RuntimeSetting, SettingGroup> GROUP_OF = new EnumMap<>(RuntimeSetting.class);

    static {
        for (var group : values()) {
            for (var member : group.members) {
                if (GROUP_OF.put(member, group) != null) {
                    throw new IllegalStateException("Setting in two groups: " + member);
                }
            }
        }
    }

    private final String title;
    private final String explanation;
    private final List<RuntimeSetting> members;

    SettingGroup(String title, String explanation, RuntimeSetting... members) {
        this.title = title;
        this.explanation = explanation;
        this.members = Arrays.asList(members);
    }

    /** The group a setting belongs to, or null when it stands alone. */
    public static SettingGroup of(RuntimeSetting setting) {
        return GROUP_OF.get(setting);
    }
}
