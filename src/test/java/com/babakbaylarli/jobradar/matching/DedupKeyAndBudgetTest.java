package com.babakbaylarli.jobradar.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.babakbaylarli.jobradar.source.Budget;
import com.babakbaylarli.jobradar.source.Job;
import com.babakbaylarli.jobradar.source.JobType;
import com.babakbaylarli.jobradar.source.Market;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class DedupKeyAndBudgetTest {

    private static Job job(String title, String company, Budget budget) {
        return new Job("x", "1", title, company, "https://x.test/1", List.of(), "", null, null, Market.INTERNATIONAL, JobType.REMOTE_JOB, budget);
    }

    @Test
    void sameTitleAndCompanyGiveTheSameKeyRegardlessOfCaseAndPunctuation() {
        assertThat(DedupKey.of(job("Senior Java Engineer (Remote)", "Acme, Inc.", null)))
                .isEqualTo(DedupKey.of(job("senior java engineer - remote", "ACME INC", null)));
    }

    @Test
    void differentCompaniesOrTitlesGiveDifferentKeys() {
        assertThat(DedupKey.of(job("Senior Java Engineer", "Acme", null))).isNotEqualTo(DedupKey.of(job("Senior Java Engineer", "Globex", null)));
        assertThat(DedupKey.of(job("Senior Java Engineer", "Acme", null))).isNotEqualTo(DedupKey.of(job("Junior Java Engineer", "Acme", null)));
    }

    @Test
    void withoutACompanyTheBudgetIdentifiesTheProject() {
        Job a = job("Spring Boot payment microservice", "", Budget.of(500, 1500, "USD", false));
        Job b = job("Spring Boot payment microservice!", "", Budget.of(500, 1500, "USD", false));
        Job other = job("Spring Boot payment microservice", "", Budget.of(500, 3000, "USD", false));

        assertThat(DedupKey.of(a)).isEqualTo(DedupKey.of(b)).isNotEqualTo(DedupKey.of(other));
    }

    @Test
    void tooLittleInformationMeansNoKeyAtAll() {
        assertThat(DedupKey.of(job("Developer", "Acme", null))).isNull();            // generic short title
        assertThat(DedupKey.of(job("Senior Java Engineer", "", null))).isNull();     // no company, no budget
    }

    @Test
    void cyrillicTitlesAreNormalizedToo() {
        assertThat(DedupKey.of(job("Разработка Java-сервиса оплаты", "ООО Пример", null)))
                .isEqualTo(DedupKey.of(job("разработка java сервиса оплаты!", "ооо  пример", null)));
    }

    @Test
    void budgetDisplayAndCeiling() {
        assertThat(Budget.of(500, 1500, "usd", false).display()).isEqualTo("500 - 1,500 USD");
        assertThat(Budget.of(20, 40, "EUR", true).display()).isEqualTo("20 - 40 EUR / hour");
        assertThat(Budget.of(null, 4000, "USD", false).ceiling()).isEqualByComparingTo(new BigDecimal("4000"));
        assertThat(Budget.of(0, 0, "USD", false)).isNull();
    }
}
