package com.babakbaylarli.jobradar.matching;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UrlNormalizerTest {

    @Test
    void stripsTrackingParametersFragmentAndTrailingSlash() {
        assertThat(UrlNormalizer.normalize("HTTPS://Remotive.com/remote-jobs/x-1/?utm_source=a&ref=b&id=7#apply"))
                .isEqualTo("https://remotive.com/remote-jobs/x-1?id=7");
    }

    @Test
    void keepsRootPathAndMeaningfulQuery() {
        assertThat(UrlNormalizer.normalize("https://news.ycombinator.com/item?id=123")).isEqualTo("https://news.ycombinator.com/item?id=123");
        assertThat(UrlNormalizer.normalize("https://example.com/")).isEqualTo("https://example.com/");
    }

    @Test
    void sameJobWithDifferentTrackingNormalizesEqual() {
        assertThat(UrlNormalizer.normalize("https://a.test/job/1?utm_campaign=x"))
                .isEqualTo(UrlNormalizer.normalize("https://a.test/job/1/"));
    }

    @Test
    void leavesGarbageAlone() {
        assertThat(UrlNormalizer.normalize("not a url")).isEqualTo("not a url");
        assertThat(UrlNormalizer.normalize(null)).isEmpty();
    }
}
