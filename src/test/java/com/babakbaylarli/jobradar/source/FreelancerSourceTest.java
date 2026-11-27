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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class FreelancerSourceTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private FreelancerSource source(boolean secrets) {
        var props = secrets ? TestSupport.props(wm.baseUrl()) : TestSupport.propsWithoutSecrets(wm.baseUrl());
        return new FreelancerSource(props, new HttpFetcher(props), new ObjectMapper());
    }

    @Test
    void parsesProjectsWithBudgetMarketAndType() {
        List<Job> jobs = source(true).parse(TestSupport.sample("freelancer.json"));

        assertThat(jobs).hasSize(2); // the closed project is skipped
        Job fixed = jobs.get(0);
        assertThat(fixed.source()).isEqualTo("freelancer");
        assertThat(fixed.externalId()).isEqualTo("40000001");
        assertThat(fixed.url()).isEqualTo(wm.baseUrl() + "/projects/java/Spring-Boot-payment-microservice");
        assertThat(fixed.tags()).containsExactly("Java", "Kafka");
        assertThat(fixed.market()).isEqualTo(Market.INTERNATIONAL);
        assertThat(fixed.type()).isEqualTo(JobType.FREELANCE_PROJECT);
        assertThat(fixed.budget()).isEqualTo(Budget.of(500, 1500, "USD", false));
        assertThat(fixed.salary()).isEqualTo("500 - 1,500 USD");
        assertThat(fixed.postedAt()).isEqualTo(Instant.ofEpochSecond(1795747244L));
        assertThat(fixed.description()).contains("payments microservice").contains("Kafka is a plus");
        Job hourly = jobs.get(1);
        assertThat(hourly.budget().hourly()).isTrue();
        assertThat(hourly.budget().currency()).isEqualTo("EUR");
        assertThat(hourly.budget().ceiling()).isEqualByComparingTo(new BigDecimal("40"));
    }

    @Test
    void fetchSendsQueryFullDescriptionAndOptionalToken() {
        wm.stubFor(get(urlPathEqualTo("/api/projects/0.1/projects/active/")).willReturn(aResponse().withBody(TestSupport.sample("freelancer.json"))));

        assertThat(source(true).fetch()).hasSize(2);

        wm.verify(getRequestedFor(urlPathEqualTo("/api/projects/0.1/projects/active/"))
                .withQueryParam("query", equalTo("java"))
                .withQueryParam("full_description", equalTo("true"))
                .withHeader("freelancer-oauth-v1", equalTo("fl-token")));
    }

    @Test
    void worksWithoutATokenAndSendsNoAuthHeader() {
        wm.stubFor(get(urlPathEqualTo("/api/projects/0.1/projects/active/")).willReturn(aResponse().withBody(TestSupport.sample("freelancer.json"))));

        assertThat(source(false).fetch()).hasSize(2);
        assertThat(source(false).unavailableReason()).isEmpty();
        wm.verify(getRequestedFor(urlPathEqualTo("/api/projects/0.1/projects/active/"))
                .withoutHeader("freelancer-oauth-v1"));
    }

    @Test
    void apiErrorStatusBecomesASourceError() {
        assertThatThrownBy(() -> source(true).parse("{\"status\":\"error\",\"message\":\"rate limited\"}"))
                .isInstanceOf(SourceException.class).hasMessageContaining("rate limited");
    }
}
