package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.enums.ProductCategory;

import java.text.Normalizer;
import java.util.List;

/**
 * Maps Open Food Facts (and the sibling Open Beauty / Open Products Facts)
 * category tags (e.g. {@code en:beverages}) to our {@link ProductCategory} enum.
 * An OFF product carries MANY tags at once (coffee has {@code en:coffees} AND
 * {@code en:beverages} AND the very broad {@code en:plant-based-foods}), so we
 * scan our categories in a fixed PRIORITY order and return the first whose
 * signature tags appear — the more distinctive domains (cleaning, beverages,
 * dairy) win over the broad pantry catch-all.
 *
 * <p>Both English ({@code en:}) AND Portuguese ({@code pt:}) tags are matched:
 * many Brazilian OFF entries are categorized ONLY in Portuguese (e.g. Neston is
 * tagged {@code pt:cereais} with no {@code en:} equivalent), so an en:-only
 * mapper silently dropped their category. Input is accent-folded before matching
 * so {@code pt:laticínios} hits the accent-free {@code pt:laticinios} tag.
 *
 * <p>Broad parent tags like {@code en:plant-based-foods} are deliberately NOT
 * used — they cover almost every non-animal food and would mis-bucket coffee,
 * pasta and chocolate as PRODUCE. Only specific fruit/vegetable tags map to
 * PRODUCE.
 */
public final class OpenFoodFactsCategoryMapper {

    private OpenFoodFactsCategoryMapper() {}

    private record Rule(ProductCategory category, List<String> tags) {}

    // Priority order: first rule whose tag matches wins. Distinctive domains
    // before the broad GROCERIES catch-all; BEVERAGES before PRODUCE so coffee/
    // tea/juices don't fall through to a fruit tag.
    private static final List<Rule> RULES = List.of(
        new Rule(ProductCategory.CLEANING, List.of(
            "en:cleaning-products", "en:household-cleaning", "en:household-supplies",
            "en:laundry", "en:dishwashing", "en:surface-cleaners", "en:disinfectants",
            "en:detergents", "en:air-fresheners", "en:insecticides", "en:garbage-bags",
            "en:bleaches", "en:fabric-softeners",
            "pt:produtos-de-limpeza", "pt:limpeza", "pt:limpesa", "pt:detergentes",
            "pt:desinfetantes", "pt:amaciantes", "pt:agua-sanitaria", "pt:limpadores",
            "pt:lava-roupas", "pt:sabao-em-po", "pt:inseticidas", "pt:sacos-de-lixo",
            "pt:desengordurantes")),
        new Rule(ProductCategory.PERSONAL_CARE, List.of(
            "en:personal-care", "en:beauty", "en:cosmetics", "en:hair-care",
            "en:skin-care", "en:oral-hygiene", "en:toothpastes", "en:deodorants",
            "en:soaps", "en:shampoos", "en:hygiene",
            "en:perfumes", "en:anti-perspirants", "en:sunscreens", "en:moisturizers",
            "en:makeup", "en:make-up", "en:lipsticks", "en:nail-polishes", "en:razors",
            "en:sanitary-pads", "en:diapers", "en:face-creams", "en:body-lotions",
            "en:face-scrubs",
            "pt:higiene", "pt:higiene-pessoal", "pt:cuidados-pessoais", "pt:cosmeticos",
            "pt:beleza", "pt:sabonetes", "pt:xampus", "pt:condicionadores",
            "pt:desodorantes", "pt:cremes-dentais", "pt:creme-dental", "pt:pasta-de-dentes",
            "pt:escovas-de-dentes", "pt:enxaguantes-bucais", "pt:higiene-bucal",
            "pt:absorventes", "pt:fraldas",
            "pt:perfumes", "pt:perfume", "pt:maquiagem", "pt:batom", "pt:esmaltes",
            "pt:hidratantes", "pt:hidratante", "pt:esfoliantes", "pt:esfoliante",
            "pt:protecao-solar", "pt:protetores-solares", "pt:clareador",
            "pt:tonico-facial", "pt:locao-para-pele", "pt:barbeadores")),
        new Rule(ProductCategory.HEALTH, List.of(
            "en:dietary-supplements", "en:supplements", "en:pharmaceuticals",
            "en:vitamins", "en:sport-nutrition", "en:protein-supplements",
            "en:medicines",
            "pt:suplementos", "pt:suplementos-alimentares", "pt:vitaminas",
            "pt:medicamentos", "pt:remedios")),
        // Before the food rules — pet food carries en:meats / cereal tags that
        // would otherwise bucket it as MEAT_DAIRY / GROCERIES.
        new Rule(ProductCategory.PET_SUPPLIES, List.of(
            "en:pet-food", "en:pet-foods", "en:foods-for-animals", "en:animal-feed",
            "en:cat-food", "en:dog-food", "en:cats", "en:dogs",
            "pt:racao", "pt:racoes", "pt:alimento-para-animais",
            "pt:alimento-para-caes", "pt:alimento-para-gatos",
            "pt:comida-para-caes", "pt:comida-para-gatos",
            "pt:racao-para-caes", "pt:racao-para-gatos", "pt:petiscos-para-caes")),
        new Rule(ProductCategory.BEVERAGES, List.of(
            "en:beverages", "en:drinks", "en:waters", "en:juices", "en:sodas",
            "en:soft-drinks", "en:alcoholic-beverages", "en:beers", "en:wines",
            "en:coffees", "en:coffee", "en:teas", "en:tea", "en:energy-drinks",
            "en:plant-based-beverages", "en:mate",
            "pt:bebidas", "pt:refrigerantes", "pt:sucos", "pt:aguas", "pt:agua",
            "pt:cervejas", "pt:vinhos", "pt:cafes", "pt:cafe", "pt:chas", "pt:cha",
            "pt:bebidas-alcoolicas", "pt:energeticos")),
        new Rule(ProductCategory.MEAT_DAIRY, List.of(
            "en:dairies", "en:dairy", "en:milks", "en:cheeses", "en:yogurts",
            "en:butters", "en:creams", "en:meats", "en:beef", "en:pork",
            "en:poultry", "en:chicken", "en:fishes", "en:seafood", "en:eggs",
            "en:sausages", "en:hams", "en:prepared-meats",
            "pt:laticinios", "pt:leites", "pt:leite", "pt:queijos", "pt:queijo",
            "pt:iogurtes", "pt:iogurte", "pt:manteigas", "pt:cremes-de-leite",
            "pt:carnes", "pt:carne", "pt:carne-bovina", "pt:frango", "pt:aves",
            "pt:peixes", "pt:pescados", "pt:ovos", "pt:embutidos", "pt:presuntos",
            "pt:linguicas", "pt:salsichas")),
        new Rule(ProductCategory.BAKERY, List.of(
            "en:breads", "en:bread", "en:bakery", "en:bakery-products",
            "en:pastries", "en:cakes", "en:viennoiseries",
            "pt:paes", "pt:pao", "pt:padaria", "pt:panificados", "pt:bolos")),
        new Rule(ProductCategory.PRODUCE, List.of(
            "en:fruits", "en:vegetables", "en:fresh-vegetables", "en:fresh-fruits",
            "en:fresh-foods", "en:herbs", "en:legumes-and-their-products",
            "pt:frutas", "pt:legumes", "pt:verduras", "pt:vegetais", "pt:hortalicas",
            "pt:frutas-frescas", "pt:legumes-frescos", "pt:ervas")),
        new Rule(ProductCategory.GROCERIES, List.of(
            "en:cereals-and-potatoes", "en:cereals", "en:pastas", "en:pasta",
            "en:rice", "en:flours", "en:legumes", "en:beans", "en:snacks",
            "en:chips-and-crackers", "en:chocolates", "en:confectioneries",
            "en:candies", "en:sugars", "en:oils-and-fats", "en:sauces",
            "en:condiments", "en:spices", "en:seasonings", "en:canned-foods",
            "en:frozen-foods", "en:soups", "en:grocery", "en:groceries",
            "en:sweeteners", "en:breakfasts", "en:cocoa-and-its-products",
            "en:biscuits", "en:cookies", "en:cereal-bars",
            "pt:cereais", "pt:cereais-matinais", "pt:massas", "pt:macarrao", "pt:arroz",
            "pt:farinhas", "pt:farinha", "pt:feijao", "pt:feijoes", "pt:leguminosas",
            "pt:snacks", "pt:salgadinhos", "pt:chocolates", "pt:chocolate", "pt:doces",
            "pt:balas", "pt:acucares", "pt:acucar", "pt:oleos", "pt:azeites",
            "pt:molhos", "pt:condimentos", "pt:temperos", "pt:especiarias",
            "pt:enlatados", "pt:conservas", "pt:congelados", "pt:sopas", "pt:biscoitos",
            "pt:bolachas", "pt:cafe-da-manha", "pt:barras-de-cereais"))
    );

    /**
     * @param categoryTags comma- or space-separated OPF category tags,
     *                     e.g. {@code "en:beverages, en:sodas, pt:refrigerantes"}
     * @return best matching {@link ProductCategory}, or {@link ProductCategory#OTHER}
     */
    public static ProductCategory map(String categoryTags) {
        if (categoryTags == null || categoryTags.isBlank()) return ProductCategory.OTHER;
        var lower = foldAccents(categoryTags).toLowerCase();
        for (var rule : RULES) {
            for (var tag : rule.tags()) {
                if (containsTag(lower, tag)) return rule.category();
            }
        }
        return ProductCategory.OTHER;
    }

    // OFF pt: tags sometimes keep accents (pt:laticínios); fold them so they hit
    // the accent-free tags above. en: tags are ASCII, so folding is a no-op there.
    private static String foldAccents(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }

    // Match on a whole tag token, not a substring, so "en:tea" doesn't fire on
    // "en:steak" and "en:coffee" doesn't fire on "en:coffee-substitutes"-style
    // false neighbours. Tags are delimited by commas/spaces in the OFF field.
    private static boolean containsTag(String tagsLower, String tag) {
        var index = tagsLower.indexOf(tag);
        while (index >= 0) {
            var before = index == 0 ? ' ' : tagsLower.charAt(index - 1);
            var afterIndex = index + tag.length();
            var after = afterIndex >= tagsLower.length() ? ' ' : tagsLower.charAt(afterIndex);
            var boundedBefore = before == ' ' || before == ',';
            var boundedAfter = after == ' ' || after == ',';
            if (boundedBefore && boundedAfter) return true;
            index = tagsLower.indexOf(tag, index + 1);
        }
        return false;
    }
}
