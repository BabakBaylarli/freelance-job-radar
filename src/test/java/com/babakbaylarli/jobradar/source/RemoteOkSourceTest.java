package com.babakbaylarli.jobradar.source;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
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

class RemoteOkSourceTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private RemoteOkSource source() {
        var props = TestSupport.props(wm.baseUrl());
        return new RemoteOkSource(props, new HttpFetcher(props), new ObjectMapper());
    }

    @Test
    void skipsLegalNoticeAndParsesJobs() {
        List<Job> jobs = source().parse(TestSupport.sample("remoteok.json"));

        assertThat(jobs).hasSize(3);
        Job java = jobs.get(0);
        assertThat(java.source()).isEqualTo("remoteok");
        assertThat(java.externalId()).isEqualTo("1100001");
        assertThat(java.title()).isEqualTo("Senior Java Engineer");
        assertThat(java.company()).isEqualTo("Acme Cloud");
        assertThat(java.tags()).containsExactly("java", "spring", "aws", "backend", "Worldwide");
        assertThat(java.salary()).isEqualTo("$100,000 - $140,000");
        assertThat(java.postedAt()).isEqualTo(Instant.parse("2026-11-25T10:12:00Z"));
        assertThat(java.description()).contains("microservices with Java and Spring Boot");
    }

    @Test
    void linksBackToTheRemoteOkJobPage() {
        List<Job> jobs = source().parse(TestSupport.sample("remoteok.json"));

        assertThat(jobs).allSatisfy(job -> assertThat(job.url()).startsWith("https://remoteok.com/remote-jobs/"));
    }

    @Test
    void handlesMissingDateAndPartialSalary() {
        Job payments = source().parse(TestSupport.sample("remoteok.json")).get(2);

        assertThat(payments.salary()).isEqualTo("$80,000");
        assertThat(payments.postedAt()).isEqualTo(Instant.ofEpochSecond(1795650000L)); // falls back to epoch
        assertThat(source().parse(TestSupport.sample("remoteok.json")).get(1).salary()).isNull();
    }

    @Test
    void fetchUsesApiEndpoint() {
        wm.stubFor(get(urlEqualTo("/api")).willReturn(aResponse().withBody(TestSupport.sample("remoteok.json"))));

        assertThat(source().fetch()).hasSize(3);
        wm.verify(1, getRequestedFor(urlEqualTo("/api")));
    }

    @Test
    void unexpectedPayloadShapeIsASourceError() {
        assertThatThrownBy(() -> source().parse("{\"error\":\"nope\"}")).isInstanceOf(SourceException.class);
    }
}
