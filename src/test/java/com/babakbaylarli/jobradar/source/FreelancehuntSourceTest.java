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

class FreelancehuntSourceTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private FreelancehuntSource source(boolean secrets) {
        var props = secrets ? TestSupport.props(wm.baseUrl()) : TestSupport.propsWithoutSecrets(wm.baseUrl());
        return new FreelancehuntSource(props, new HttpFetcher(props), new ObjectMapper());
    }

    @Test
    void parsesProjectsWithBudgetAndTheWebLink() {
        List<Job> jobs = source(true).parse(TestSupport.sample("freelancehunt.json"));

        assertThat(jobs).hasSize(2);
        Job first = jobs.get(0);
        assertThat(first.source()).isEqualTo("freelancehunt");
        assertThat(first.externalId()).isEqualTo("1666882");
        assertThat(first.url()).isEqualTo("https://freelancehunt.example/project/rest-api/1666882.html");
        assertThat(first.title()).isEqualTo("REST API на Spring Boot для магазину");
        assertThat(first.tags()).containsExactly("Java", "PostgreSQL", "remote");
        assertThat(first.budget()).isEqualTo(Budget.of(30000, null, "UAH", false));
        assertThat(first.market()).isEqualTo(Market.UA);
        assertThat(first.type()).isEqualTo(JobType.FREELANCE_PROJECT);
        assertThat(first.postedAt()).isEqualTo(Instant.parse("2026-11-27T08:40:10Z"));
        assertThat(first.description()).contains("Потрібен бекенд на Spring Boot.");
    }

    @Test
    void neverStoresAPrivateEmployersName() {
        List<Job> jobs = source(true).parse(TestSupport.sample("freelancehunt.json"));

        assertThat(jobs.get(0).company()).isEmpty();          // private person: no name stored
        assertThat(jobs.get(1).company()).isEqualTo("WEWER"); // company name is fine
        assertThat(jobs.get(1).budget()).isNull();
    }

    @Test
    void sendsTheBearerToken() {
        wm.stubFor(get(urlPathEqualTo("/v2/projects")).willReturn(aResponse().withBody(TestSupport.sample("freelancehunt.json"))));

        assertThat(source(true).fetch()).hasSize(2);

        wm.verify(getRequestedFor(urlPathEqualTo("/v2/projects")).withHeader("Authorization", equalTo("Bearer fh-token")));
    }

    @Test
    void isSkippedWithAClearReasonWithoutAToken() {
        assertThat(source(false).unavailableReason()).hasValueSatisfying(reason -> assertThat(reason).contains("FREELANCEHUNT_TOKEN"));
        assertThat(source(true).unavailableReason()).isEmpty();
    }
}
