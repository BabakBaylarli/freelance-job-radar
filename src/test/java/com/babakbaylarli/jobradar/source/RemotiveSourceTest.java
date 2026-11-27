package com.babakbaylarli.jobradar.source;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
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

class RemotiveSourceTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private RemotiveSource source() {
        var props = TestSupport.props(wm.baseUrl());
        return new RemotiveSource(props, new HttpFetcher(props), new ObjectMapper());
    }

    @Test
    void parsesSamplePayloadIntoNormalizedJobs() {
        List<Job> jobs = source().parse(TestSupport.sample("remotive.json"));

        assertThat(jobs).hasSize(3); // the entry without a url is skipped
        Job first = jobs.get(0);
        assertThat(first.source()).isEqualTo("remotive");
        assertThat(first.externalId()).isEqualTo("1920001");
        assertThat(first.title()).isEqualTo("Senior Java Backend Engineer");
        assertThat(first.company()).isEqualTo("PayLane");
        assertThat(first.url()).isEqualTo("https://remotive.com/remote-jobs/software-dev/senior-java-backend-engineer-1920001");
        assertThat(first.tags()).containsExactly("java", "spring", "kafka", "payments", "Europe");
        assertThat(first.salary()).isEqualTo("$90k - $120k");
        assertThat(first.postedAt()).isEqualTo(Instant.parse("2026-11-25T09:14:03Z"));
        assertThat(first.description()).contains("Spring Boot & Kafka").contains("- PostgreSQL").doesNotContain("<");
        assertThat(jobs.get(1).salary()).isNull();
    }

    @Test
    void fetchCallsCategoryEndpointAndLinksBackToRemotive() {
        wm.stubFor(get(urlPathEqualTo("/api/remote-jobs"))
                .withQueryParam("category", equalTo("software-dev"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(TestSupport.sample("remotive.json"))));

        List<Job> jobs = source().fetch();

        assertThat(jobs).hasSize(3).allSatisfy(job -> assertThat(job.url()).startsWith("https://remotive.com/"));
        wm.verify(1, getRequestedFor(urlPathEqualTo("/api/remote-jobs")));
    }

    @Test
    void invalidJsonIsASourceError() {
        assertThatThrownBy(() -> source().parse("<html>not json</html>")).isInstanceOf(SourceException.class);
    }

    @Test
    void exposesConfiguredInterval() {
        assertThat(source().pollInterval()).hasHours(6);
        assertThat(source().id()).isEqualTo("remotive");
    }
}
