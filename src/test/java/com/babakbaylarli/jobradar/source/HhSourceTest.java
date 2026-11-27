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

class HhSourceTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private HhSource source(boolean secrets) {
        var props = secrets ? TestSupport.props(wm.baseUrl()) : TestSupport.propsWithoutSecrets(wm.baseUrl());
        return new HhSource(props, new HttpFetcher(props), new ObjectMapper());
    }

    @Test
    void parsesVacanciesAndMapsRoubleCode() {
        List<Job> jobs = source(true).parse(TestSupport.sample("hh.json"));

        assertThat(jobs).hasSize(2); // the entry without id/url is skipped
        Job first = jobs.get(0);
        assertThat(first.source()).isEqualTo("hh");
        assertThat(first.externalId()).isEqualTo("900001");
        assertThat(first.title()).isEqualTo("Java-разработчик (проектная работа)");
        assertThat(first.company()).isEqualTo("ООО Пример");
        assertThat(first.url()).isEqualTo("https://hh.example/vacancy/900001");
        assertThat(first.budget()).isEqualTo(Budget.of(150000, 250000, "RUB", false));
        assertThat(first.tags()).containsExactly("Удалённо", "Проект", "Москва");
        assertThat(first.postedAt()).isEqualTo(Instant.parse("2026-11-26T07:13:35Z"));
        assertThat(first.description()).contains("Опыт Java от 2 лет").contains("Разработка сервисов платежей").doesNotContain("highlighttext");
        assertThat(first.market()).isEqualTo(Market.RU);
        assertThat(first.type()).isEqualTo(JobType.VACANCY);
        Job second = jobs.get(1);
        assertThat(second.budget()).isEqualTo(Budget.of(null, 4000, "USD", false)); // legacy "salary" object
    }

    @Test
    void fetchUsesTheCurrentSearchParametersAndTheRequiredUserAgentHeader() {
        wm.stubFor(get(urlPathEqualTo("/vacancies")).willReturn(aResponse().withBody(TestSupport.sample("hh.json"))));

        assertThat(source(true).fetch()).hasSize(2);

        wm.verify(getRequestedFor(urlPathEqualTo("/vacancies"))
                .withQueryParam("text", equalTo("java"))
                .withQueryParam("work_format", equalTo("REMOTE"))
                .withQueryParam("employment_form", equalTo("PROJECT"))
                .withQueryParam("per_page", equalTo("50"))
                .withHeader("HH-User-Agent", equalTo("FreelanceJobRadar/1.0 (me@example.org)")));
    }

    @Test
    void isSkippedWithAClearReasonWithoutAContactEmail() {
        assertThat(source(false).unavailableReason()).hasValueSatisfying(reason -> assertThat(reason).contains("HH_CONTACT_EMAIL"));
        assertThat(source(true).unavailableReason()).isEmpty();
    }
}
