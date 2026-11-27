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

class HimalayasSourceTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private HimalayasSource source() {
        var props = TestSupport.props(wm.baseUrl());
        return new HimalayasSource(props, new HttpFetcher(props), new ObjectMapper());
    }

    @Test
    void parsesJobsAndLinksToTheCanonicalHimalayasPage() {
        List<Job> jobs = source().parse(TestSupport.sample("himalayas.json"));

        assertThat(jobs).hasSize(2);
        Job first = jobs.get(0);
        assertThat(first.source()).isEqualTo("himalayas");
        assertThat(first.title()).isEqualTo("Senior Backend Engineer (Java)");
        assertThat(first.company()).isEqualTo("Example Pay Ltd");
        assertThat(first.url()).isEqualTo("https://himalayas.example/companies/example-pay/jobs/senior-backend-engineer-java"); // no utm_source
        assertThat(first.tags()).contains("Backend-Engineer", "Senior", "Germany", "Full Time");
        assertThat(first.salary()).isEqualTo("95,000 - 130,000 USD / annual");
        assertThat(first.postedAt()).isEqualTo(Instant.ofEpochSecond(1795765987L));
        assertThat(first.description()).contains("Design Java services.").contains("- Spring Boot");
        assertThat(first.type()).isEqualTo(JobType.REMOTE_JOB);
        assertThat(jobs.get(1).salary()).isNull();
    }

    @Test
    void collapsesAHugeLocationListToWorldwide() {
        String countries = java.util.stream.IntStream.range(0, 60).mapToObj(i -> "\"Country" + i + "\"").collect(java.util.stream.Collectors.joining(","));
        String json = "{\"jobs\":[{\"title\":\"Java Dev\",\"guid\":\"https://himalayas.example/j/1\",\"companyName\":\"X\",\"locationRestrictions\":[" + countries + "],\"categories\":[\"Java\"]}]}";

        Job job = source().parse(json).get(0);

        assertThat(job.tags()).containsExactly("Java", "Worldwide");
    }

    @Test
    void searchesOncePerQueryAndSendsTheApiKeyAsBearer() {
        wm.stubFor(get(urlPathEqualTo("/jobs/api/search")).willReturn(aResponse().withBody(TestSupport.sample("himalayas.json"))));

        assertThat(source().fetch()).hasSize(2);

        wm.verify(1, getRequestedFor(urlPathEqualTo("/jobs/api/search"))
                .withQueryParam("q", equalTo("java"))
                .withHeader("Authorization", equalTo("Bearer him-key")));
    }

    @Test
    void rateLimitIsReportedAsSourceFailureWithoutRetryStorm() {
        wm.stubFor(get(urlPathEqualTo("/jobs/api/search")).willReturn(aResponse().withStatus(429)
                .withBody("{\"ok\":false,\"errors\":\"Rate limit reached.\"}")));

        assertThatThrownBy(() -> source().fetch()).isInstanceOf(SourceException.class).hasMessageContaining("429");
        wm.verify(1, getRequestedFor(urlPathEqualTo("/jobs/api/search")));
    }

    @Test
    void rejectedBodyWithOkFalseIsAnError() {
        assertThatThrownBy(() -> source().parse("{\"ok\":false,\"errors\":\"Rate limit reached.\"}"))
                .isInstanceOf(SourceException.class).hasMessageContaining("Rate limit");
    }
}
