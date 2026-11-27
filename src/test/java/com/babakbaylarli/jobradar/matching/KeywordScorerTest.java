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
            List.of(new Keyword("java", 3), new Keyword("spring", 3), new Keyword("spring boot", 1),
                    new Keyword("kafka", 2), new Keyword("c++", 1), new Keyword("fintech", 2)),
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
}
