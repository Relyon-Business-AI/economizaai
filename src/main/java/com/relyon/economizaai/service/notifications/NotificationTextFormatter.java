package com.relyon.economizaai.service.notifications;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The one way money and dates are written in outbound texts (push, e-mail,
 * WhatsApp): Brazilian format, whatever the host's default locale is.
 */
public final class NotificationTextFormatter {

    private static final Locale BRAZIL = Locale.forLanguageTag("pt-BR");
    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("dd/MM");

    private NotificationTextFormatter() {
    }

    /** "1.234,50" — for templates that already carry the "R$" prefix. */
    public static String amount(BigDecimal value) {
        var safeValue = value == null ? BigDecimal.ZERO : value;
        var format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(BRAZIL));
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(safeValue);
    }

    /** "R$ 1.234,50". */
    public static String money(BigDecimal value) {
        return "R$ " + amount(value);
    }

    /** "03/11". */
    public static String dayMonth(LocalDate date) {
        return date == null ? "" : date.format(DAY_MONTH);
    }
}
