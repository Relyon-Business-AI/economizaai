package com.relyon.economizaai.service.extraction;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Keyword classifier for the fine taxonomy — the CHEAP first pass of the hybrid.
 * It no longer pretends to answer everything: it returns a {@link Confidence}, and
 * only HIGH-confidence results are trusted deterministically. LOW / NONE are routed
 * to the LLM (see {@code AiFineCategoryClassifier} / the recategorization sweep).
 *
 * <p>Confidence is derived from two signals, no magic threshold:
 * <ul>
 *   <li><b>Conflict:</b> if the name matches keywords from MORE THAN ONE category
 *       (e.g. "MACARRÃO PICANHA" → mercearia AND carnes; "SUCO LIMÃO" → bebidas AND
 *       hortifrúti) it's ambiguous → LOW. First-rule-wins used to hide this.</li>
 *   <li><b>Strength:</b> STRONG keywords are the product itself (picanha, cerveja,
 *       iogurte); WEAK ones are generic/context-dependent (leite→leite de coco,
 *       molho, água). A single category matched only via WEAK keywords → LOW.</li>
 * </ul>
 * HIGH = exactly one category matched AND at least one match is STRONG.
 */
public final class ProductNameSubcategoryClassifier {

    private ProductNameSubcategoryClassifier() {}

    public enum Confidence { HIGH, LOW, NONE }

    /** slug = the subcategory slug to use when HIGH (meaningless for NONE). */
    public record Result(String slug, Confidence confidence) {
        public static final Result NONE = new Result(null, Confidence.NONE);
    }

    /**
     * category = parent category slug usado p/ detectar conflito.
     * exclude = contextos que DESQUALIFICAM a regra (ex.: "agua" é fruta/bebida, mas "agua
     * sanitaria" não; "limao" é fruta, mas "suco de limao"/"detergente limao" não). Se qualquer
     * exclude casar, a regra é ignorada — deixa "agua"/"limao"/"cha" serem fortes no caso puro.
     */
    private record Rule(String slug, String category, List<String> strong, List<String> weak, List<String> exclude) {
        Rule(String slug, String category, List<String> strong) { this(slug, category, strong, List.of(), List.of()); }
        Rule(String slug, String category, List<String> strong, List<String> weak) { this(slug, category, strong, weak, List.of()); }
    }

    private static final List<Rule> RULES = List.of(
        new Rule("outros-laticinios", "laticinios-ovos", List.of(
            "creme de leite", "leite condensado", "leite em po", "manteiga", "margarina",
            "requeijao", "nata", "chantilly")),
        new Rule("queijos", "laticinios-ovos", List.of(
            "queijo", "mussarela", "mucarela", "parmesao", "provolone", "gorgonzola",
            "ricota", "catupiry", "cheddar", "minas", "coalho")),
        new Rule("iogurtes", "laticinios-ovos", List.of("iogurte", "yogurt", "activia", "danoninho")),
        // instant noodles before meat/produce so flavor names don't win
        new Rule("graos-massas", "mercearia", List.of(
            "macarrao", "miojo", "lamen", "cup noodles", "nissin", "espaguete", "talharim")),
        new Rule("aves", "carnes", List.of(
            "frango", "peito de frango", "coxa", "sobrecoxa", "asa de frango", "peru", "chester")),
        new Rule("suinos", "carnes", List.of(
            "suino", "bacon", "lombo", "pernil", "costelinha", "linguica", "salsicha",
            "presunto", "mortadela", "salame", "paio", "pancetta")),
        new Rule("peixes", "carnes", List.of(
            "peixe", "tilapia", "salmao", "sardinha", "atum", "merluza", "bacalhau", "pescada")),
        new Rule("frutos-do-mar", "carnes", List.of("camarao", "lula", "polvo", "marisco", "frutos do mar")),
        new Rule("carnes-bovinas", "carnes", List.of(
            "picanha", "alcatra", "coxao", "patinho", "acem", "costela", "hamburguer", "bife",
            "maminha", "fraldinha", "cupim", "contra file", "file mignon"),
            List.of("carne", "bovina", "moida", "musculo")),
        new Rule("cervejas", "bebidas-alcoolicas", List.of(
            "cerveja", "chopp", "heineken", "budweiser", "stella artois", "eisenbahn",
            "itaipava", "amstel", "spaten", "corona", "becks")),
        new Rule("vinhos", "bebidas-alcoolicas", List.of("vinho", "espumante", "prosecco", "champagne")),
        new Rule("destilados", "bebidas-alcoolicas", List.of(
            "whisky", "vodka", "cachaca", " gin ", " rum ", "tequila", "conhaque", "licor",
            "aperitivo", "campari", "steinhaeger")),
        new Rule("bebidas-alcoolicas-outros", "bebidas-alcoolicas", List.of(
            "bebida alcoolica mista", "sidra", "sake", "saque")),
        new Rule("refrigerantes", "bebidas", List.of(
            "refrigerante", "coca cola", "coca-cola", "guarana", "pepsi", "fanta", "sprite", "refri"),
            List.of("soda", "tonica")),
        new Rule("sucos", "bebidas", List.of("suco", "nectar", "del valle", "tampico")),
        // "agua" forte, exceto quando é produto de limpeza/saúde (água sanitária/oxigenada).
        new Rule("agua", "bebidas", List.of("agua"), List.of(), List.of("sanitaria", "oxigenada")),
        new Rule("cafe-cha", "bebidas", List.of(
            "cafe", "cappuccino", "capuccino", "cha preto", "cha verde", "cha branco", "cha gelado",
            "cha mate", "mate leao", "ice tea", "erva mate"), List.of(" cha ", " mate ")),
        new Rule("bebidas-outros", "bebidas", List.of(
            "energetico", "red bull", "monster energy", "isotonico", "gatorade", "powerade")),
        new Rule("paes", "padaria", List.of("baguete", "bisnaga", "pao de forma", "pao frances"), List.of("pao")),
        new Rule("bolos-confeitaria", "padaria", List.of("bolo", "torta", "croissant", "sonho", "panetone")),
        new Rule("chocolates", "doces-snacks", List.of(
            "chocolate", "bombom", "nescau", "toddy", "achocolatado", "kitkat", "lacta",
            "garoto", "diamante negro", "talento")),
        new Rule("salgadinhos", "doces-snacks", List.of(
            "salgadinho", "batata frita", "batata palha", "banana chips", "chips", "doritos",
            "ruffles", "cheetos", "pipoca", "amendoim", "torcida", "fandangos")),
        new Rule("doces", "doces-snacks", List.of(
            "bala", "pirulito", "chiclete", "chicle", "drops", "halls", "tic tac", "biscoito",
            "bolacha", "cookie", "wafer", "gelatina", "pacoca", "brigadeiro")),
        new Rule("frutas", "hortifruti", List.of(
            "banana", " maca ", "mamao", "abacaxi", "melancia", "manga", "morango",
            "abacate", "melao", "kiwi", "goiaba", "ameixa"),
            List.of(),
            // fruta é SABOR comum de doce/bebida/industrializado — só vale no caso puro
            // (casos reais de prod: DROPS HALLS MORANGO, RED BULL MACA, CHICLE UVA, BANANA CHIPS)
            List.of("suco", "nectar", "refri", "refres", "energet", "isot", "drops", "halls",
                "chicle", "goma", "bala", "pirulito", "tic tac", "gelat", "iogurte", " iog ",
                "sorvete", "picole", "doce", "chips", "palha", "vinagre", "vinag", "choc",
                "bolacha", "biscoito", "bisc", "cuca", "bolo", "trufa", "tru ")),
        // cítricos/uva/pera: fruta no caso puro, mas são sabor comum de suco/refri/doce/limpeza →
        // fortes SÓ sem esse contexto (exclusões), senão "suco de limão" viraria fruta.
        new Rule("frutas", "hortifruti",
            List.of("limao", "laranja", " uva", "pera", "tangerina", "maracuja"),
            List.of(),
            List.of("suco", "nectar", "refri", "refrigerante", "detergente", "sabao", "amaciante",
                "essencia", "desinfetante", "aromatiz", "limpador", " cha ", "gelatina", "iogurte",
                "bala", "chiclete", "pirulito", "bolacha", "biscoito", "sorvete", "picole")),
        new Rule("legumes-verduras", "hortifruti", List.of(
            "tomate", "batata", "cebola", "alface", "cenoura", "brocolis", "couve", "abobrinha",
            "pepino", "pimentao", "alho", "beterraba", "repolho", "mandioca", "abobora",
            "chuchu", "berinjela", "quiabo", "vagem", "espinafre", "rucula"),
            List.of(),
            // batata PALHA/FRITA/CHIPS é snack industrializado, não hortifrúti (BATATA PALHA KIFRIT)
            List.of("palha", "frita", "chips")),
        new Rule("temperos-ervas", "hortifruti", List.of(
            "salsa", "cebolinha", "coentro", "manjericao", "alecrim", "hortela", "tomilho")),
        new Rule("enlatados-conservas", "mercearia", List.of(
            "em conserva", "em lata", "enlatado", "extrato de tomate", "milho verde", "ervilha",
            "palmito", "azeitona", "seleta")),
        new Rule("molhos-temperos", "mercearia", List.of(
            "ketchup", "catchup", "maionese", "mostarda", "azeite", "vinagre", "oregano",
            "pimenta do reino", "shoyu",
            // compostos de sal (NUNCA "sal" solto: RUFFLES SAL, MARG C/SAL, MANT S/SAL)
            "sal grosso", "sal refinado", "sal marinho", "sal rosa", "flor de sal", "sal light",
            "sal para churrasco", "sal churrasco"), List.of("molho", " oleo ", "oleo de", "tempero", "caldo")),
        new Rule("matinais", "mercearia", List.of("granola", "sucrilhos", "aveia em flocos", "mingau"), List.of("cereal")),
        new Rule("graos-massas", "mercearia", List.of(
            "arroz", "feijao", "farinha", "lentilha", "grao de bico", "polenta", "fuba",
            "cuscuz", "quinoa"), List.of(" massa ", "aveia", "trigo")),
        new Rule("papel", "casa-descartaveis", List.of(
            "papel higienico", "guardanapo", "toalha de papel", "lenco de papel")),
        new Rule("descartaveis", "casa-descartaveis", List.of(
            "copo descartavel", "prato descartavel", "papel aluminio", "filme pvc",
            "luva", "luvas", "saco de lixo", "saco para lixo", "saco lixo", "touca descartavel")),
        // Itens não-alimentares de uso doméstico: antes caíam no LLM e viravam "não-mercado".
        // Keywords específicas (evita "cola" solto, que casaria "coca cola").
        new Rule("utilidades", "casa-descartaveis", List.of(
            "pilha", "bateria", "lampada", "lamp led", "fosforo", "carvao", "isqueiro", "vela",
            "durepoxi", "super bonder", "cola bastao", "cola branca", "cola instantanea",
            "cola quente", "fita adesiva", "fita crepe", "durex", "prego", "parafuso",
            "lanterna", "cadeado", "pregador de roupa")),
        // generic dairy fallbacks LATE + WEAK (ao leite / leite de coco must not force dairy)
        new Rule("leite", "laticinios-ovos", List.of(), List.of("leite")),
        new Rule("ovos", "laticinios-ovos", List.of("duzia de ovos"), List.of("ovo ", "ovos")));

    /** Classify with a confidence verdict. HIGH is safe to trust; LOW/NONE → LLM. */
    public static Result classify(String name) {
        if (name == null || name.isBlank()) return Result.NONE;
        var folded = " " + foldAccents(name).toLowerCase() + " ";
        var categories = new LinkedHashSet<String>();
        String firstStrongSlug = null;
        String firstAnySlug = null;
        for (var rule : RULES) {
            if (anyMatch(folded, rule.exclude())) continue; // contexto desqualifica (ex.: "suco de limao")
            var strong = anyMatch(folded, rule.strong());
            var weak = !strong && anyMatch(folded, rule.weak());
            if (!strong && !weak) continue;
            categories.add(rule.category());
            if (firstAnySlug == null) firstAnySlug = rule.slug();
            if (strong && firstStrongSlug == null) firstStrongSlug = rule.slug();
        }
        if (categories.isEmpty()) return Result.NONE;
        if (categories.size() > 1) return new Result(firstStrongSlug != null ? firstStrongSlug : firstAnySlug, Confidence.LOW);
        // single category:
        if (firstStrongSlug != null) return new Result(firstStrongSlug, Confidence.HIGH);
        return new Result(firstAnySlug, Confidence.LOW); // weak-only
    }

    private static boolean anyMatch(String foldedName, List<String> keywords) {
        for (var keyword : keywords) {
            if (foldedName.contains(keyword)) return true;
        }
        return false;
    }

    private static String foldAccents(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }
}
