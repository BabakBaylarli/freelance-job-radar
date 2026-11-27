package com.babakbaylarli.jobradar.apply;

import java.math.BigDecimal;

/** A suggested bid: amount in the job's currency, delivery days, and an optional note for the owner (e.g. a rate warning). */
public record Bid(BigDecimal amount, String currency, int days, String note) {

    public String display() {
        return amount.stripTrailingZeros().toPlainString() + " " + currency + " in " + days + " day" + (days == 1 ? "" : "s");
    }
}
