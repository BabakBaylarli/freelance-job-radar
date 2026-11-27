package com.babakbaylarli.jobradar.source;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.babakbaylarli.jobradar.TestSupport;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class WeWorkRemotelySourceTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private WeWorkRemotelySource source() {
        var props = TestSupport.props(wm.baseUrl());
        return new WeWorkRemotelySource(props, new HttpFetcher(props));
    }

    @Test
    void parsesRssItems() {
        List<Job> jobs = source().parse(TestSupport.sample("wwr.xml"));

        assertThat(jobs).hasSize(3);
        Job first = jobs.get(0);
        assertThat(first.source()).isEqualTo("weworkremotely");
        assertThat(first.company()).isEqualTo("Northwind Labs");
        assertThat(first.title()).isEqualTo("Backend Engineer (Java/Spring)");
        assertThat(first.url()).isEqualTo("https://weworkremotely.com/remote-jobs/northwind-labs-backend-engineer-java-spring");
        assertThat(first.externalId()).isEqualTo(first.url());
        assertThat(first.tags()).containsExactly("Java", "Spring Boot", "PostgreSQL", "Back-End Programming", "Full-Time", "Anywhere in the World");
        assertThat(first.description()).isEqualTo("Northwind builds payments APIs.\n\nYou will own Java microservices.");
        assertThat(first.postedAt()).isEqualTo(Instant.parse("2026-11-25T14:03:11Z"));
    }

    @Test
    void decodesEntitiesAndToleratesMissingFields() {
        List<Job> jobs = source().parse(TestSupport.sample("wwr.xml"));

        assertThat(jobs.get(1).company()).isEqualTo("Orbit & Co");
        Job odd = jobs.get(2);
        assertThat(odd.company()).isEmpty();
        assertThat(odd.title()).isEqualTo("Untitled without company separator");
        assertThat(odd.postedAt()).isNull();
    }

    @Test
    void rejectsXmlWithDoctypeToPreventXxe() {
        String evil = "<?xml version=\"1.0\"?><!DOCTYPE rss [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><rss><channel><item><title>&x;</title><link>http://a</link></item></channel></rss>";

        assertThatThrownBy(() -> source().parse(evil)).isInstanceOf(SourceException.class);
    }

    @Test
    void mergesFeedsAndDeduplicatesAcrossThem() {
        wm.stubFor(get("/feed-a.rss").willReturn(aResponse().withBody(TestSupport.sample("wwr.xml"))));
        wm.stubFor(get("/feed-b.rss").willReturn(aResponse().withBody(TestSupport.sample("wwr.xml"))));

        assertThat(source().fetch()).hasSize(3);
    }

    @Test
    void oneBrokenFeedDoesNotLoseTheOther() {
        wm.stubFor(get("/feed-a.rss").willReturn(aResponse().withStatus(404)));
        wm.stubFor(get("/feed-b.rss").willReturn(aResponse().withBody(TestSupport.sample("wwr.xml"))));

        assertThat(source().fetch()).hasSize(3);
    }

    @Test
    void failsWhenEveryFeedFails() {
        wm.stubFor(get("/feed-a.rss").willReturn(aResponse().withStatus(404)));
        wm.stubFor(get("/feed-b.rss").willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> source().fetch()).isInstanceOf(SourceException.class);
    }
}
