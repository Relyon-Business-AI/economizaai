package com.relyon.economizaai.service.extraction;

import java.text.Normalizer;
import java.util.List;

/**
 * Maps a product's (normalized) name to a fine taxonomy leaf {@code slug} using
 * Portuguese grocery keywords. OFF coverage of Brazilian EANs is poor (~0.3% hit
 * on real data), so the product NAME is the reliable signal for the fine splits
 * the new taxonomy is about — especially dairy≠meat and alcoholic≠non-alcoholic.
 *
 * <p>Priority order matters: more specific phrases first (e.g. "creme de leite" →
 * outros-laticinios before "leite" → leite; "leite de coco" is an accepted
 * mis-hit). Whole-word-ish containment on an accent-folded, lowercased name.
 */
public final class ProductNameLeafClassifier {

    private ProductNameLeafClassifier() {}

    private record Rule(String slug, List<String> keywords) {}

    private static final List<Rule> RULES = List.of(
        // --- dairy (before meat) ---
        new Rule("outros-laticinios", List.of(
            "creme de leite", "leite condensado", "leite em po", "manteiga", "margarina",
            "requeijao", "nata", "chantilly")),
        new Rule("queijos", List.of(
            "queijo", "mussarela", "mucarela", "parmesao", "provolone", "gorgonzola",
            "ricota", "catupiry", "cheddar", "minas", "coalho")),
        new Rule("iogurtes", List.of("iogurte", "yogurt", "activia", "danoninho")),
        // --- meat ---
        new Rule("aves", List.of(
            "frango", "peito de frango", "coxa", "sobrecoxa", "asa de frango", "peru",
            "chester", "tender")),
        new Rule("suinos", List.of(
            "suino", "bacon", "lombo", "pernil", "costelinha", "linguica", "salsicha",
            "presunto", "mortadela", "salame", "paio", "pancetta")),
        new Rule("peixes", List.of(
            "peixe", "tilapia", "salmao", "sardinha", "atum", "merluza", "bacalhau",
            "pescada", "file de peixe")),
        new Rule("frutos-do-mar", List.of("camarao", "lula", "polvo", "marisco", "frutos do mar")),
        new Rule("carnes-bovinas", List.of(
            "carne", "bovina", "picanha", "alcatra", "coxao", "patinho", "acem", "costela",
            "moida", "hamburguer", "bife", "maminha", "fraldinha", "cupim", "contra file",
            "file mignon", "musculo", "cupim")),
        // --- beverages: alcoholic ---
        new Rule("cervejas", List.of(
            "cerveja", "chopp", "heineken", "brahma", "skol", "antarctica", "budweiser",
            "stella", "eisenbahn", "itaipava", "amstel", "spaten")),
        new Rule("vinhos", List.of("vinho", "espumante", "prosecco", "champagne")),
        new Rule("destilados", List.of(
            "whisky", "vodka", "cachaca", " gin ", " rum ", "tequila", "conhaque", "licor",
            "aperitivo", "campari", "steinhaeger")),
        // --- beverages: non-alcoholic ---
        new Rule("refrigerantes", List.of(
            "refrigerante", "coca cola", "coca-cola", "guarana", "pepsi", "fanta", "sprite",
            "soda", "tonica", "refri")),
        new Rule("sucos", List.of("suco", "nectar", "del valle", "tampico")),
        new Rule("agua", List.of("agua mineral", "agua com gas", "agua sem gas", "agua tonica")),
        new Rule("cafe-cha", List.of("cafe", "cappuccino", "capuccino", " cha ", "cha preto", "cha verde", " mate ", "erva mate")),
        // --- bakery ---
        new Rule("paes", List.of("pao", "baguete", "bisnaga", "pao de forma", "pao frances")),
        new Rule("bolos-confeitaria", List.of("bolo", "torta", "croissant", "sonho", "panetone")),
        // --- sweets & snacks ---
        new Rule("chocolates", List.of(
            "chocolate", "bombom", "nescau", "toddy", "achocolatado", "kitkat", "lacta",
            "garoto", "diamante negro", "talento")),
        new Rule("salgadinhos", List.of(
            "salgadinho", "batata frita", "chips", "doritos", "ruffles", "cheetos", "pipoca",
            "amendoim", "torcida", "fandangos")),
        new Rule("doces", List.of(
            "bala", "pirulito", "chiclete", "biscoito", "bolacha", "cookie", "wafer",
            "gelatina", "goma", "paconga", "pacoca", "brigadeiro")),
        // --- produce ---
        new Rule("frutas", List.of(
            "banana", " maca ", "laranja", "mamao", "abacaxi", "uva", "melancia", "manga",
            "morango", "limao", "pera", "abacate", "melao", "kiwi", "tangerina", "goiaba",
            " meloa", "ameixa")),
        new Rule("legumes-verduras", List.of(
            "tomate", "batata", "cebola", "alface", "cenoura", "brocolis", "couve", "abobrinha",
            "pepino", "pimentao", "alho", "beterraba", "repolho", "mandioca", "abobora",
            "chuchu", "berinjela", "quiabo", "vagem", "espinafre", "rucula")),
        new Rule("temperos-ervas", List.of(
            "salsa", "cebolinha", "coentro", "manjericao", "alecrim", "hortela", "tomilho")),
        // --- pantry ---
        new Rule("graos-massas", List.of(
            "arroz", "feijao", "macarrao", " massa ", "farinha", "lentilha", "grao de bico",
            "aveia", "trigo", "polenta", "fuba", "cuscuz", "quinoa")),
        new Rule("enlatados-conservas", List.of(
            "em conserva", "em lata", "enlatado", "extrato de tomate", "milho verde", "ervilha",
            "palmito", "azeitona", "seleta")),
        new Rule("molhos-temperos", List.of(
            "molho", "ketchup", "catchup", "maionese", "mostarda", "azeite", " oleo ", "oleo de",
            "vinagre", "tempero", "oregano", "pimenta do reino", "shoyu", "caldo")),
        new Rule("matinais", List.of("cereal", "granola", "sucrilhos", "aveia em flocos", "mingau")),
        // --- generic dairy fallbacks (LATE: "ao leite" in chocolate/sweets must lose to them) ---
        new Rule("leite", List.of("leite")),
        new Rule("ovos", List.of("ovo ", "ovos", "duzia")),
        // --- home & disposables ---
        new Rule("papel", List.of("papel higienico", "guardanapo", "toalha de papel", "lenco de papel")),
        new Rule("descartaveis", List.of("copo descartavel", "prato descartavel", "saco de lixo", "papel aluminio", "filme pvc")));

    /** Fine leaf slug matching the name, or null when nothing matches. */
    public static String classify(String name) {
        if (name == null || name.isBlank()) return null;
        var folded = " " + foldAccents(name).toLowerCase() + " ";
        for (var rule : RULES) {
            for (var keyword : rule.keywords()) {
                if (folded.contains(keyword)) return rule.slug();
            }
        }
        return null;
    }

    private static String foldAccents(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }
}
