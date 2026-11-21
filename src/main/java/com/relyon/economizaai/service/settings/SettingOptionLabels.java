package com.relyon.economizaai.service.settings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How each accepted value of an option setting reads in the admin. The value itself (what is
 * stored and sent back) stays the code name; only what the admin sees is the label.
 */
public final class SettingOptionLabels {

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("WATCHED_MARKETS_UNLIMITED", "Mercados acompanhados sem limite"),
            Map.entry("HISTORY_FULL_RANGE", "Histórico completo"),
            Map.entry("PUSH_AND_EMAIL_DELIVERY", "Avisos por push e e-mail"),
            Map.entry("RECEIPT_UPLOAD_UNLIMITED", "Notas sem limite por mês"),
            Map.entry("BASKET_OPTIMIZATION", "Plano de compras entre mercados"),
            Map.entry("CSV_EXPORT", "Exportar histórico em planilha"),
            Map.entry("PHOTO_EXTRACTION", "Nota por foto"),
            Map.entry("CUSTOM_CATEGORIES_UNLIMITED", "Categorias próprias sem limite"),
            Map.entry("OFF", "Desligado"),
            Map.entry("SHADOW", "Só medir"),
            Map.entry("ON", "Ligado"),
            Map.entry("ADMINS", "Só administradores"),
            Map.entry("EVERYONE", "Todos os usuários"),
            Map.entry("hortifruti", "Hortifrúti"),
            Map.entry("carnes", "Carnes"),
            Map.entry("laticinios-ovos", "Laticínios e ovos"),
            Map.entry("padaria", "Padaria"),
            Map.entry("mercearia", "Mercearia"),
            Map.entry("doces-snacks", "Doces e snacks"),
            Map.entry("congelados", "Congelados"),
            Map.entry("bebidas", "Bebidas"),
            Map.entry("bebidas-alcoolicas", "Bebidas alcoólicas"),
            Map.entry("limpeza", "Limpeza"),
            Map.entry("higiene", "Higiene pessoal"),
            Map.entry("casa-descartaveis", "Casa e descartáveis"),
            Map.entry("pet", "Pet"),
            Map.entry("combustivel", "Combustível"),
            Map.entry("outros", "Outros"),
            Map.entry("MONDAY", "Segunda-feira"),
            Map.entry("TUESDAY", "Terça-feira"),
            Map.entry("WEDNESDAY", "Quarta-feira"),
            Map.entry("THURSDAY", "Quinta-feira"),
            Map.entry("FRIDAY", "Sexta-feira"),
            Map.entry("SATURDAY", "Sábado"),
            Map.entry("SUNDAY", "Domingo"));

    private SettingOptionLabels() {}

    public static String of(String option) {
        return LABELS.get(option);
    }

    /** The labels of the given options, in the same order. */
    public static Map<String, String> of(List<String> options) {
        var labels = new LinkedHashMap<String, String>();
        options.forEach(option -> labels.put(option, LABELS.getOrDefault(option, option)));
        return labels;
    }
}
