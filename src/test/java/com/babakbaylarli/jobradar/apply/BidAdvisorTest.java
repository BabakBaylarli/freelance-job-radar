package com.babakbaylarli.jobradar.apply;

import static org.assertj.core.api.Assertions.assertThat;

import com.babakbaylarli.jobradar.TestSupport;
import com.babakbaylarli.jobradar.config.JobRadarProperties;
import com.babakbaylarli.jobradar.source.Budget;
import com.babakbaylarli.jobradar.source.Job;
import com.babakbaylarli.jobradar.source.JobType;
import com.babakbaylarli.jobradar.source.Market;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BidAdvisorTest {

    private final BidAdvisor advisor = new BidAdvisor(TestSupport.withApply(TestSupport.props("http://unused"),
            new JobRadarProperties.Apply(10, Map.of(), Duration.ofMinutes(2), Map.of("USD", new BigDecimal("30")), Map.of("USD", new BigDecimal("400")),
                    5, List.of(), "config/cv.pdf", ZoneId.of("Asia/Baku"), new JobRadarProperties.Smtp("localhost", 25, false, false), null)));

    private static Job job(Budget budget) {
        return new Job("freelancer", "1", "t", "", "https://x.test/1", List.of(), "", null, null, Market.INTERNATIONAL, JobType.FREELANCE_PROJECT, budget);
    }

    @Test
    void fixedProjectsGetTheMiddleOfTheClientsRange() {
        Bid bid = advisor.suggest(job(Budget.of(500, 1500, "USD", false))).orElseThrow();

        assertThat(bid.amount()).isEqualByComparingTo("1000");
        assertThat(bid.currency()).isEqualTo("USD");
        assertThat(bid.days()).isEqualTo(5);
        assertThat(bid.note()).isNull();
        assertThat(bid.display()).isEqualTo("1000 USD in 5 days");
    }

    @Test
    void aSingleBudgetFigureIsDiscountedAndRoundedToFive() {
        assertThat(advisor.suggest(job(Budget.of(null, 2000, "USD", false))).orElseThrow().amount()).isEqualByComparingTo("1700"); // 85%
        assertThat(advisor.suggest(job(Budget.of(null, 1237, "USD", false))).orElseThrow().amount()).isEqualByComparingTo("1050"); // 1051.45 -> nearest 5
    }

    @Test
    void neverBidsBelowTheConfiguredMinimumAndWarnsWhenItIsAboveTheClientsBudget() {
        Bid raised = advisor.suggest(job(Budget.of(100, 600, "USD", false))).orElseThrow(); // middle 350 < 400
        assertThat(raised.amount()).isEqualByComparingTo("400");
        assertThat(raised.note()).isNull();

        Bid tooCheap = advisor.suggest(job(Budget.of(100, 250, "USD", false))).orElseThrow();
        assertThat(tooCheap.amount()).isEqualByComparingTo("400");
        assertThat(tooCheap.note()).contains("above the client's maximum budget");
    }

    @Test
    void hourlyProjectsUseTheRateRangeAndTheMinimumHourlyRate() {
        assertThat(advisor.suggest(job(Budget.of(40, 60, "USD", true))).orElseThrow().amount()).isEqualByComparingTo("50");
        assertThat(advisor.suggest(job(Budget.of(10, 20, "USD", true))).orElseThrow().amount()).isEqualByComparingTo("30");
    }

    @Test
    void noBudgetOrNoCurrencyMeansNoSuggestion() {
        assertThat(advisor.suggest(job(null))).isEmpty();
        assertThat(advisor.suggest(job(Budget.of(100, 200, null, false)))).isEmpty();
    }

    @Test
    void smallAmountsAreRoundedToWholeUnits() {
        assertThat(BidAdvisor.round(new BigDecimal("37.6"))).isEqualByComparingTo("38");
        assertThat(BidAdvisor.round(new BigDecimal("812.37"))).isEqualByComparingTo("810");
    }
}
