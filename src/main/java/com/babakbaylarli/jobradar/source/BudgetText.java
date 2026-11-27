package com.babakbaylarli.jobradar.source;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a budget out of free text such as "$500 - $1,500", "до 15 000 ₽", "£400" or "Hourly: $30-$60". Used for e-mail
 * alerts and Telegram posts where there is no structured budget field. Returns {@code null} when no amount is found.
 */
public final class BudgetText {

    private static final Pattern MONEY_PREFIX = Pattern.compile(
            "(?<cur>[$€£₽₴₼])\\s?(?<a>\\d[\\d,.\\s\\u00a0]*\\d|\\d)(?:\\s*[-–—]\\s*[$€£₽₴₼]?\\s?(?<b>\\d[\\d,.\\s\\u00a0]*\\d|\\d))?");
    private static final Pattern MONEY_SUFFIX = Pattern.compile(
            "(?<a>\\d[\\d,.\\s\\u00a0]*\\d|\\d)(?:\\s*[-–—]\\s*(?<b>\\d[\\d,.\\s\\u00a0]*\\d|\\d))?\\s*(?<cur>₽|руб\\.?|грн\\.?|₴|₼|AZN|USD|EUR|GBP|RUB|UAH|man\\.?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HOURLY = Pattern.compile("(/\\s?h(ou)?r|hourly|per hour|в час|за годину|saatlıq)", Pattern.CASE_INSENSITIVE);

    private BudgetText() {}

    /** @param defaultCurrency used when the matched text names a currency this class does not know (may be null) */
    public static Budget parse(String text, String defaultCurrency) {
        if (text == null || text.isBlank()) {
            return null;
        }
        boolean hourly = HOURLY.matcher(text).find();
        Matcher prefix = MONEY_PREFIX.matcher(text);
        if (prefix.find()) {
            return Budget.of(amount(prefix.group("a")), amount(prefix.group("b")), currencyOf(prefix.group("cur"), defaultCurrency), hourly);
        }
        Matcher suffix = MONEY_SUFFIX.matcher(text);
        if (suffix.find()) {
            return Budget.of(amount(suffix.group("a")), amount(suffix.group("b")), currencyOf(suffix.group("cur"), defaultCurrency), hourly);
        }
        return null;
    }

    private static String currencyOf(String symbol, String defaultCurrency) {
        return switch (symbol.toLowerCase(Locale.ROOT)) {
            case "$", "usd" -> "USD";
            case "€", "eur" -> "EUR";
            case "£", "gbp" -> "GBP";
            case "₽", "rub", "руб", "руб." -> "RUB";
            case "₴", "грн", "грн.", "uah" -> "UAH";
            case "₼", "azn", "man", "man." -> "AZN";
            default -> defaultCurrency;
        };
    }

    private static BigDecimal amount(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("[\\s\\u00a0,]", "");
        try {
            return digits.isEmpty() ? null : new BigDecimal(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
