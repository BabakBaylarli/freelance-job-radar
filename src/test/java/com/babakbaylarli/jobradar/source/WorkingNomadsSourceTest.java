package com.babakbaylarli.jobradar.source;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
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

class WorkingNomadsSourceTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private WorkingNomadsSource source() {
        var props = TestSupport.props(wm.baseUrl());
        return new WorkingNomadsSource(props, new HttpFetcher(props), new ObjectMapper());
    }

    @Test
    void parsesTheJsonArray() {
        List<Job> jobs = source().parse(TestSupport.sample("workingnomads.json"));

        assertThat(jobs).hasSize(2);
        Job first = jobs.get(0);
        assertThat(first.source()).isEqualTo("workingnomads");
        assertThat(first.externalId()).isEqualTo("2049641");
        assertThat(first.url()).isEqualTo("https://workingnomads.example/job/go/2049641/");
        assertThat(first.company()).isEqualTo("Example Labs");
        assertThat(first.tags()).containsExactly("java", "spring", "kafka", "Development", "Europe, Latin America");
        assertThat(first.postedAt()).isEqualTo(Instant.parse("2026-11-25T14:36:50Z"));
        assertThat(first.description()).contains("About the Role").contains("Build Spring Boot services.");
        assertThat(jobs.get(1).postedAt()).isNull(); // unparsable date does not drop the job
    }

    @Test
    void fetchUsesTheExposedJobsEndpoint() {
        wm.stubFor(get(urlEqualTo("/api/exposed_jobs/")).willReturn(aResponse().withBody(TestSupport.sample("workingnomads.json"))));

        assertThat(source().fetch()).hasSize(2);
    }

    @Test
    void nonArrayPayloadIsASourceError() {
        assertThatThrownBy(() -> source().parse("{\"detail\":\"throttled\"}")).isInstanceOf(SourceException.class);
    }
}
