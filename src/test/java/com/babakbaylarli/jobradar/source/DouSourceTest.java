package com.babakbaylarli.jobradar.source;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.babakbaylarli.jobradar.TestSupport;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class DouSourceTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private DouSource source() {
        var props = TestSupport.props(wm.baseUrl());
        return new DouSource(props, new HttpFetcher(props));
    }

    @Test
    void splitsTheTitleIntoRoleCompanyAndLocation() {
        List<Job> jobs = source().parse(TestSupport.sample("dou.xml"));

        assertThat(jobs).hasSize(2);
        Job first = jobs.get(0);
        assertThat(first.source()).isEqualTo("dou");
        assertThat(first.externalId()).isEqualTo("381243");
        assertThat(first.title()).isEqualTo("Senior Java Developer (Part-time)");
        assertThat(first.company()).isEqualTo("Example Software");
        assertThat(first.tags()).containsExactly("Львів", "віддалено");
        assertThat(first.market()).isEqualTo(Market.UA);
        assertThat(first.type()).isEqualTo(JobType.VACANCY);
        assertThat(first.postedAt()).isEqualTo(Instant.parse("2026-11-26T12:14:22Z"));
        assertThat(first.description()).contains("Build Spring Boot services for payments.").contains("- Kafka").doesNotContain("<");
    }

    @Test
    void titleWithoutCompanyIsKept() {
        Job job = source().parse(TestSupport.sample("dou.xml")).get(1);

        assertThat(job.title()).isEqualTo("Technical Writer, віддалено");
        assertThat(job.company()).isEmpty();
    }

    @Test
    void fetchRequestsTheCategoryWithTheRemoteFlag() {
        wm.stubFor(get(urlEqualTo("/vacancies/feeds/?category=Java&remote")).willReturn(aResponse().withBody(TestSupport.sample("dou.xml"))));

        assertThat(source().fetch()).hasSize(2);
        wm.verify(1, getRequestedFor(urlEqualTo("/vacancies/feeds/?category=Java&remote")));
    }
}
