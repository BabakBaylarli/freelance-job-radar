package com.babakbaylarli.jobradar.source;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.babakbaylarli.jobradar.TestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class JobicySourceTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private JobicySource source() {
        var props = TestSupport.props(wm.baseUrl());
        return new JobicySource(props, new HttpFetcher(props), new ObjectMapper());
    }

    @Test
    void parsesJobsKeepingTheOriginalJobUrl() {
        List<Job> jobs = source().parse(TestSupport.sample("jobicy.json"));

        assertThat(jobs).hasSize(2);
        Job first = jobs.get(0);
        assertThat(first.source()).isEqualTo("jobicy");
        assertThat(first.externalId()).isEqualTo("157144");
        assertThat(first.url()).isEqualTo("https://jobicy.example/jobs/157144-backend-engineer-java");
        assertThat(first.company()).isEqualTo("Example Exchange");
        assertThat(first.tags()).containsExactly("Software Engineering", "Full-Time", "Europe, UK", "Senior");
        assertThat(first.salary()).isEqualTo("90,000 - 120,000 EUR / yearly");
        assertThat(first.postedAt()).isEqualTo(Instant.parse("2026-11-26T04:30:34Z"));
        assertThat(first.description()).isEqualTo("Build trading systems in Java.");
        assertThat(jobs.get(1).salary()).isNull();
    }

    @Test
    void fetchAsksForEachConfiguredTag() {
        wm.stubFor(get(urlPathEqualTo("/api/v2/remote-jobs")).willReturn(aResponse().withBody(TestSupport.sample("jobicy.json"))));

        assertThat(source().fetch()).hasSize(2); // same two jobs for both... deduplicated by id

        wm.verify(getRequestedFor(urlPathEqualTo("/api/v2/remote-jobs")).withQueryParam("tag", equalTo("java")).withQueryParam("count", equalTo("50")));
    }
}
