package com.babakbaylarli.jobradar.source;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

/** A numeric budget of a freelance project (or a salary range). {@code max} and {@code currency} may be null. */
public record Budget(BigDecimal min, BigDecimal max, String currency, boolean hourly) {

    public static Budget of(Number min, Number max, String currency, boolean hourly) {
        BigDecimal lo = positive(min);
        BigDecimal hi = positive(max);
        if (lo == null && hi == null) {
            return null;
        }
        return new Budget(lo, hi, currency == null || currency.isBlank() ? null : currency.strip().toUpperCase(Locale.ROOT), hourly);
    }

    private static BigDecimal positive(Number n) {
        if (n == null) {
            return null;
        }
        BigDecimal value = n instanceof BigDecimal b ? b : new BigDecimal(n.toString());
        return value.signum() > 0 ? value.setScale(2, RoundingMode.HALF_UP) : null;
    }

    /** The most generous figure: what the client may pay at most. */
    public BigDecimal ceiling() {
        return max != null ? max : min;
    }

    public String display() {
        NumberFormat fmt = NumberFormat.getIntegerInstance(Locale.US);
        String range = min != null && max != null && min.compareTo(max) != 0
                ? fmt.format(min) + " - " + fmt.format(max)
                : fmt.format(ceiling());
        return range + (currency == null ? "" : " " + currency) + (hourly ? " / hour" : "");
    }
}
