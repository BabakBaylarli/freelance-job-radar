package com.babakbaylarli.jobradar.apply;

import com.babakbaylarli.jobradar.config.JobRadarProperties;
import com.babakbaylarli.jobradar.source.Budget;
import com.babakbaylarli.jobradar.source.Job;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Suggests a bid for platforms that need an amount and delivery days. It is only a suggestion: the owner sees it in the
 * draft and can change it ("bid 650 5"). Rules: fixed projects get the middle of the client's range (85% of the maximum
 * when there is no minimum), hourly projects the middle of the rate range, never below the owner's configured minimum.
 */
@Component
public class BidAdvisor {

    private static final BigDecimal MIDPOINT = new BigDecimal("0.5");
    private static final BigDecimal DISCOUNT = new BigDecimal("0.85");

    private final JobRadarProperties.Apply config;

    public BidAdvisor(JobRadarProperties properties) {
        this.config = properties.apply();
    }

    public Optional<Bid> suggest(Job job) {
        Budget budget = job.budget();
        if (budget == null || budget.currency() == null || budget.ceiling() == null) {
            return Optional.empty();
        }
        String currency = budget.currency();
        BigDecimal ceiling = budget.ceiling();
        BigDecimal floor = budget.hourly() ? config.minHourlyRate().get(currency) : config.minFixedBid().get(currency);
        BigDecimal amount = budget.min() != null && budget.max() != null && budget.min().compareTo(budget.max()) != 0
                ? budget.min().add(budget.max().subtract(budget.min()).multiply(MIDPOINT))
                : budget.hourly() ? ceiling : ceiling.multiply(DISCOUNT);
        String note = null;
        if (floor != null && amount.compareTo(floor) < 0) {
            amount = floor;
            if (floor.compareTo(ceiling) > 0) {
                note = "Your minimum (%s %s) is above the client's maximum budget (%s %s).".formatted(
                        plain(floor), currency, plain(ceiling), currency);
            }
        }
        return Optional.of(new Bid(round(amount), currency, config.defaultDays(), note));
    }

    /** Whole units, to the nearest 5 above 100 - bids like "812.37" look automated. */
    static BigDecimal round(BigDecimal amount) {
        if (amount.compareTo(BigDecimal.valueOf(100)) >= 0) {
            return amount.divide(BigDecimal.valueOf(5), 0, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(5));
        }
        return amount.setScale(0, RoundingMode.HALF_UP);
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
