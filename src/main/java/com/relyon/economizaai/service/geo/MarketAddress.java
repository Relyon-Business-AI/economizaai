package com.relyon.economizaai.service.geo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A market's address broken into the parts a geocoder can use. SEFAZ prints it as
 * {@code "RUA X , 34 , , CENTRO , PENAPOLIS , SP"} — empty complements and the bairro in
 * the middle make Nominatim miss even well-known streets, while
 * {@code "RUA X 34, PENAPOLIS, SP, Brasil"} hits. Some portals print the CNPJ in the
 * address slot; that is no address at all.
 */
public record MarketAddress(String street, String number, String city, String uf, String cep) {

    private static final Set<String> BRAZIL_UFS = Set.of(
            "AC", "AL", "AP", "AM", "BA", "CE", "DF", "ES", "GO", "MA", "MT", "MS", "MG", "PA",
            "PB", "PR", "PE", "PI", "RJ", "RN", "RS", "RO", "RR", "SC", "SP", "SE", "TO");
    private static final Pattern CNPJ_PLACEHOLDER = Pattern.compile("^\\d{2}\\.?\\d{3}\\.?\\d{3}/?\\d{4}-?\\d{2}$");
    private static final Pattern CITY_DASH_UF = Pattern.compile("^(.+?)\\s*-\\s*([A-Za-z]{2})$");
    private static final Pattern STREET_NUMBER = Pattern.compile("^0*(\\d+[A-Za-z]?)$");
    private static final String BRASIL_SUFFIX = ", Brasil";

    /** Parses a SEFAZ-printed address; empty when it is blank, a CNPJ, or carries no recognizable city + UF. */
    public static Optional<MarketAddress> parse(String rawAddress) {
        if (isMissing(rawAddress)) return Optional.empty();
        var segments = new ArrayList<>(Arrays.stream(rawAddress.split(","))
                .map(String::trim)
                .filter(segment -> !segment.isEmpty())
                .toList());
        splitTrailingCityDashUf(segments);
        if (segments.size() < 3) return Optional.empty();
        var uf = segments.getLast().toUpperCase(Locale.ROOT);
        if (!BRAZIL_UFS.contains(uf)) return Optional.empty();
        var city = segments.get(segments.size() - 2);
        return Optional.of(new MarketAddress(segments.getFirst(), streetNumber(segments.get(1)), city, uf, null));
    }

    /** True when there is nothing usable in the address slot (blank, or the CNPJ printed in its place). */
    public static boolean isMissing(String rawAddress) {
        return rawAddress == null || rawAddress.isBlank() || CNPJ_PLACEHOLDER.matcher(rawAddress.trim()).matches();
    }

    /** Most to least precise: street + number, then the street alone (numbering is often absent in OSM). */
    public List<String> geocodeQueries() {
        var queries = new ArrayList<String>();
        if (street == null || city == null || uf == null) return queries;
        if (number != null) queries.add(street + " " + number + ", " + city + ", " + uf + BRASIL_SUFFIX);
        queries.add(street + ", " + city + ", " + uf + BRASIL_SUFFIX);
        return queries;
    }

    /** {@code "PORTO ALEGRE - RS"} as the last segment becomes two segments: city, UF. */
    private static void splitTrailingCityDashUf(List<String> segments) {
        if (segments.isEmpty()) return;
        var matcher = CITY_DASH_UF.matcher(segments.getLast());
        if (!matcher.matches() || !BRAZIL_UFS.contains(matcher.group(2).toUpperCase(Locale.ROOT))) return;
        segments.removeLast();
        segments.add(matcher.group(1).trim());
        segments.add(matcher.group(2));
    }

    /** {@code "000186"} → {@code "186"}; {@code "0"}, {@code "S/N"} or a complement → no number. */
    private static String streetNumber(String segment) {
        var matcher = STREET_NUMBER.matcher(segment);
        if (!matcher.matches()) return null;
        var number = matcher.group(1);
        return number.equals("0") ? null : number;
    }
}
