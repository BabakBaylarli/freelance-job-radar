package com.babakbaylarli.jobradar.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.babakbaylarli.jobradar.TestSupport;
import com.babakbaylarli.jobradar.config.JobRadarProperties.Keyword;
import com.babakbaylarli.jobradar.source.Job;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class KeywordScorerTest {

    private final KeywordScorer scorer = new KeywordScorer(TestSupport.matching(
            4,
            List.of(Keyword.of("java", 3), Keyword.of("spring", 3), Keyword.of("spring boot", 1),
                    Keyword.of("kafka", 2), Keyword.of("c++", 1), Keyword.of("fintech", 2)),
            List.of("onsite", "us only")));

    private static Job job(String title, List<String> tags, String description) {
        return new Job("remotive", "1", title, "Acme", "https://x.test/1", tags, description, null, Instant.EPOCH);
    }

    @Test
    void sumsWeightsOfMatchedKeywordsAndReportsWhy() {
        ScoreResult result = scorer.score(job("Java Backend Engineer", List.of("kafka"), "Work with Spring Boot"));

        assertThat(result.score()).isEqualTo(3 + 2 + 3 + 1); // java + kafka + spring + spring boot
        assertThat(result.matchedKeywords()).containsExactlyInAnyOrder("java", "kafka", "spring", "spring boot");
        assertThat(result.matched()).isTrue();
        assertThat(result.excludedBy()).isNull();
    }

    @Test
    void matchesWholeTermsOnlyAndIgnoresCase() {
        assertThat(scorer.score(job("JavaScript developer", List.of(), "")).score()).isZero();
        assertThat(scorer.score(job("JAVA developer", List.of(), "")).matchedKeywords()).containsExactly("java");
        assertThat(scorer.score(job("Springfield analyst", List.of(), "")).score()).isZero();
    }

    @Test
    void supportsTermsWithSymbols() {
        assertThat(scorer.score(job("Senior C++ engineer", List.of(), "")).matchedKeywords()).containsExactly("c++");
        assertThat(scorer.score(job("Engineer", List.of(), "Java/Kotlin with kafka, fintech.")).matchedKeywords())
                .containsExactlyInAnyOrder("java", "kafka", "fintech");
    }

    @Test
    void eachKeywordCountsOnce() {
        ScoreResult result = scorer.score(job("Java java JAVA", List.of("java"), "java java java"));

        assertThat(result.score()).isEqualTo(3);
    }

    @Test
    void searchesTitleTagsAndDescription() {
        assertThat(scorer.score(job("Engineer", List.of("Kafka"), "")).score()).isEqualTo(2);
        assertThat(scorer.score(job("Engineer", List.of(), "We are a fintech startup")).score()).isEqualTo(2);
    }

    @Test
    void excludeKeywordVetoesAnOtherwiseGreatMatch() {
        ScoreResult result = scorer.score(job("Java Spring engineer", List.of("US only"), "Kafka, onsite in Austin"));

        assertThat(result.score()).isEqualTo(8);
        assertThat(result.matched()).isFalse();
        assertThat(result.excludedBy()).isEqualTo("onsite");
    }

    @Test
    void belowThresholdIsNotAMatch() {
        ScoreResult result = scorer.score(job("Kafka engineer", List.of(), ""));

        assertThat(result.score()).isEqualTo(2);
        assertThat(result.matched()).isFalse();
        assertThat(scorer.minScore()).isEqualTo(4);
    }

    @Test
    void exactlyAtThresholdMatches() {
        assertThat(scorer.score(job("Java", List.of(), "fintech? no. kafka")).matched()).isTrue(); // 3 + 2 = 5
        assertThat(scorer.score(job("Spring", List.of(), "")).matched()).isFalse(); // 3 < 4
        assertThat(scorer.score(job("Spring Boot", List.of(), "")).matched()).isTrue(); // 3 + 1 = 4
    }

    @Test
    void emptyConfigurationMatchesNothing() {
        KeywordScorer empty = new KeywordScorer(TestSupport.matching(1, List.of(), List.of()));

        assertThat(empty.score(job("Java", List.of(), "")).matched()).isFalse();
    }

    // ---- aliases (multi-language keywords) and minimum budget -------------------------------------------------

    private static Job project(String title, String description, com.babakbaylarli.jobradar.source.Budget budget) {
        return new Job("flru", "1", title, "", "https://x.test/p", List.of(), description, null, Instant.EPOCH,
                com.babakbaylarli.jobradar.source.Market.RU, com.babakbaylarli.jobradar.source.JobType.FREELANCE_PROJECT, budget);
    }

    @Test
    void aliasesCountAsTheSameKeywordAndAreReportedByTheirCanonicalName() {
        KeywordScorer aliased = new KeywordScorer(TestSupport.matching(3,
                List.of(Keyword.of("backend", 3, "бэкенд", "бекенд", "proqramçı backend")), List.of()));

        assertThat(aliased.score(job("Нужен бэкенд разработчик", List.of(), "")).matchedKeywords()).containsExactly("backend");
        assertThat(aliased.score(job("Ищем БЕКЕНД-программиста", List.of(), "")).matchedKeywords()).containsExactly("backend");
        assertThat(aliased.score(job("Backend developer", List.of(), "")).matchedKeywords()).containsExactly("backend");
    }

    @Test
    void anAliasAndTheTermTogetherStillCountOnce() {
        KeywordScorer aliased = new KeywordScorer(TestSupport.matching(1, List.of(Keyword.of("backend", 3, "бэкенд")), List.of()));

        assertThat(aliased.score(job("backend / бэкенд", List.of(), "backend бэкенд")).score()).isEqualTo(3);
    }

    @Test
    void aliasesRespectWordBoundariesInCyrillicToo() {
        KeywordScorer aliased = new KeywordScorer(TestSupport.matching(1, List.of(Keyword.of("java", 3, "ява")), List.of()));

        assertThat(aliased.score(job("Програмист на яву", List.of(), "")).score()).isZero();
        assertThat(aliased.score(job("Программист ява", List.of(), "")).score()).isEqualTo(3);
        assertThat(aliased.score(job("Яванский кофе", List.of(), "")).score()).isZero(); // "ява" inside a longer word
    }

    private KeywordScorer withMinBudget() {
        var base = TestSupport.matching(1, List.of(Keyword.of("java", 3)), List.of());
        var matching = new com.babakbaylarli.jobradar.config.JobRadarProperties.Matching(base.include(), base.exclude(), base.minScore(),
                base.notifyMaxAge(), java.util.Map.of("USD", new java.math.BigDecimal("500"), "rub", new java.math.BigDecimal("30000")));
        return new KeywordScorer(matching);
    }

    @Test
    void freelanceProjectsBelowTheMinimumBudgetOfTheirCurrencyAreVetoed() {
        KeywordScorer scorer = withMinBudget();

        ScoreResult cheap = scorer.score(project("Java task", "", com.babakbaylarli.jobradar.source.Budget.of(100, 300, "USD", false)));
        ScoreResult enough = scorer.score(project("Java task", "", com.babakbaylarli.jobradar.source.Budget.of(100, 800, "USD", false)));

        assertThat(cheap.matched()).isFalse();
        assertThat(cheap.excludedBy()).isEqualTo("budget below 500 USD");
        assertThat(cheap.score()).isEqualTo(3); // the reason stays visible
        assertThat(enough.matched()).isTrue();
    }

    @Test
    void budgetRuleIsPerCurrencyAndCaseInsensitive() {
        KeywordScorer scorer = withMinBudget();

        assertThat(scorer.score(project("Java task", "", com.babakbaylarli.jobradar.source.Budget.of(10000, null, "RUB", false))).excludedBy())
                .isEqualTo("budget below 30000 RUB");
        assertThat(scorer.score(project("Java task", "", com.babakbaylarli.jobradar.source.Budget.of(10, null, "EUR", false))).matched())
                .isTrue(); // no minimum configured for EUR
    }

    @Test
    void budgetRuleIgnoresHourlyRatesUnknownBudgetsAndNonProjects() {
        KeywordScorer scorer = withMinBudget();

        assertThat(scorer.score(project("Java task", "", com.babakbaylarli.jobradar.source.Budget.of(5, 10, "USD", true))).matched()).isTrue();
        assertThat(scorer.score(project("Java task", "", null)).matched()).isTrue();
        Job salaried = new Job("hh", "1", "Java dev", "", "https://x.test/v", List.of(), "", null, Instant.EPOCH,
                com.babakbaylarli.jobradar.source.Market.RU, com.babakbaylarli.jobradar.source.JobType.VACANCY,
                com.babakbaylarli.jobradar.source.Budget.of(100, 200, "USD", false));
        assertThat(scorer.score(salaried).matched()).isTrue(); // only freelance projects are subject to the minimum
    }
}
