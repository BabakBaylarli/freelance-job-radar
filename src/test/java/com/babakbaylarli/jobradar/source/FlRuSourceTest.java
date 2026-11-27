package com.babakbaylarli.jobradar.source;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.babakbaylarli.jobradar.TestSupport;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class FlRuSourceTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private FlRuSource source() {
        var props = TestSupport.props(wm.baseUrl());
        return new FlRuSource(props, new HttpFetcher(props));
    }

    @Test
    void extractsTheBudgetFromTheTitleAndKeepsTheRussianMarket() {
        List<Job> jobs = source().parse(TestSupport.sample("flru.xml"));

        assertThat(jobs).hasSize(3);
        Job first = jobs.get(0);
        assertThat(first.source()).isEqualTo("flru");
        assertThat(first.externalId()).isEqualTo("5530732");
        assertThat(first.title()).isEqualTo("Доработка Java-сервиса оплаты");
        assertThat(first.budget()).isEqualTo(Budget.of(45000, null, "RUB", false));
        assertThat(first.market()).isEqualTo(Market.RU);
        assertThat(first.type()).isEqualTo(JobType.FREELANCE_PROJECT);
        assertThat(first.tags()).containsExactly("Программирование", "Java");
        assertThat(first.description()).isEqualTo("Нужно доработать сервис платежей на Spring Boot.\nИнтеграция с Kafka.");
        assertThat(first.postedAt()).isEqualTo(Instant.parse("2026-11-27T08:07:34Z"));
    }

    @Test
    void handlesMissingBudgetAndOtherCurrencies() {
        List<Job> jobs = source().parse(TestSupport.sample("flru.xml"));

        assertThat(jobs.get(1).budget()).isNull();
        assertThat(jobs.get(1).title()).isEqualTo("Бот в телеграм");
        assertThat(jobs.get(2).budget()).isEqualTo(Budget.of(1200, null, "USD", false));
        assertThat(jobs.get(2).title()).isEqualTo("Backend API for startup");
        assertThat(jobs.get(2).externalId()).isEqualTo("5530700"); // no guid: id comes from the link
    }

    @Test
    void fetchReadsEveryConfiguredFeed() {
        wm.stubFor(get(urlEqualTo("/rss/all.xml?category=5")).willReturn(aResponse().withBody(TestSupport.sample("flru.xml"))));

        assertThat(source().fetch()).hasSize(3);
    }

    @Test
    void invalidXmlIsASourceError() {
        assertThatThrownBy(() -> source().parse("<html>maintenance</html")).isInstanceOf(SourceException.class);
    }
}
