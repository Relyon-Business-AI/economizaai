package com.relyon.economizaai.service.notifications;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NotificationTextFormatterTest {

    @Test
    void money_usesBrazilianSeparatorsAndTwoDecimals() {
        assertEquals("R$ 1.234,50", NotificationTextFormatter.money(new BigDecimal("1234.5")));
        assertEquals("R$ 0,99", NotificationTextFormatter.money(new BigDecimal("0.99")));
        assertEquals("R$ 1.000.000,00", NotificationTextFormatter.money(new BigDecimal("1000000")));
    }

    @Test
    void amount_omitsTheCurrencyPrefixAndRoundsHalfUp() {
        assertEquals("19,90", NotificationTextFormatter.amount(new BigDecimal("19.9")));
        assertEquals("5,50", NotificationTextFormatter.amount(new BigDecimal("5.495")));
        assertEquals("0,00", NotificationTextFormatter.amount(null));
    }

    @Test
    void dayMonth_isDayThenMonth() {
        assertEquals("03/11", NotificationTextFormatter.dayMonth(LocalDate.of(2026, Month.NOVEMBER, 3)));
        assertEquals("", NotificationTextFormatter.dayMonth(null));
    }
}
