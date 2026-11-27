package com.babakbaylarli.jobradar.source;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.babakbaylarli.jobradar.TestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class HackerNewsSourceTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private HackerNewsSource source(boolean remoteOnly) {
        var props = TestSupport.props(wm.baseUrl(), remoteOnly);
        return new HackerNewsSource(props, new HttpFetcher(props), new ObjectMapper());
    }

    @Test
    void picksTheNewestWhoIsHiringThreadAndIgnoresWantsToBeHired() {
        assertThat(source(true).findLatestThreadId(TestSupport.sample("hn_search.json"))).isEqualTo("90000002");
    }

    @Test
    void topLevelCommentsBecomeJobs() {
        List<Job> jobs = source(true).parseThread(TestSupport.sample("hn_thread.json"));

        assertThat(jobs).hasSize(2); // onsite-only post filtered, deleted comment skipped, reply ignored
        Job first = jobs.get(0);
        assertThat(first.source()).isEqualTo("hackernews");
        assertThat(first.externalId()).isEqualTo("90000101");
        assertThat(first.url()).isEqualTo("https://news.ycombinator.com/item?id=90000101");
        assertThat(first.title()).isEqualTo("Ledgerly (YC S21) | Senior Backend Engineer | Remote (EU) | Full-time | $120k-$160k");
        assertThat(first.company()).isEqualTo("Ledgerly (YC S21)");
        assertThat(first.description()).contains("banking-as-a-service platform on Java, Spring Boot and Kafka");
        assertThat(first.postedAt()).isEqualTo(Instant.ofEpochSecond(1793613600L));
        assertThat(jobs.get(1).title()).endsWith("Java, Postgres & Elasticsearch");
    }

    @Test
    void remoteOnlyCanBeSwitchedOff() {
        assertThat(source(false).parseThread(TestSupport.sample("hn_thread.json"))).hasSize(3);
    }

    @Test
    void extractsCompanyFromHeadline() {
        assertThat(HackerNewsSource.companyFrom("Acme | Engineer | Remote")).isEqualTo("Acme");
        assertThat(HackerNewsSource.companyFrom("Just a sentence without separators")).isEmpty();
    }

    @Test
    void fetchSearchesThenReadsTheThreadThroughTheAlgoliaApi() {
        wm.stubFor(get(urlPathEqualTo("/api/v1/search_by_date")).willReturn(aResponse().withBody(TestSupport.sample("hn_search.json"))));
        wm.stubFor(get(urlPathEqualTo("/api/v1/items/90000002")).willReturn(aResponse().withBody(TestSupport.sample("hn_thread.json"))));

        assertThat(source(true).fetch()).hasSize(2);
    }

    @Test
    void failsClearlyWhenNoThreadExists() {
        assertThatThrownBy(() -> source(true).findLatestThreadId("{\"hits\":[]}"))
                .isInstanceOf(SourceException.class)
                .hasMessageContaining("No 'Who is hiring?' thread");
    }
}
