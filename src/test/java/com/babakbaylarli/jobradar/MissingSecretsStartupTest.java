package com.babakbaylarli.jobradar;

import static org.assertj.core.api.Assertions.assertThat;

import com.babakbaylarli.jobradar.source.JobSource;
import com.babakbaylarli.jobradar.source.SourceCatalog;
import com.babakbaylarli.jobradar.source.SourceCatalog.Status;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The out-of-the-box configuration: every default source enabled and not a single token, e-mail password or contact
 * address set. The app must start, health must be UP and sources that need a secret must be skipped, not crash.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "jobradar.telegram.bot-token=",
        "jobradar.ai.api-key=",
        "jobradar.sources.freelancehunt.token=",
        "jobradar.sources.hh.contact-email=",
        "jobradar.poll-tick-initial-delay=PT1H",
        "jobradar.flush-initial-delay=PT1H"})
@Testcontainers
class MissingSecretsStartupTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate rest;
    @Autowired List<JobSource> sources;
    @Autowired SourceCatalog catalog;

    @Test
    void healthIsUpAndSourcesNeedingSecretsAreSkippedWithAReason() {
        assertThat(rest.getForEntity("/actuator/health", String.class).getBody()).contains("\"status\":\"UP\"");

        var byId = catalog.entries().stream().collect(java.util.stream.Collectors.toMap(SourceCatalog.Entry::id, e -> e));
        assertThat(byId.get("hh").status()).isEqualTo(Status.SKIPPED);
        assertThat(byId.get("hh").reason()).contains("HH_CONTACT_EMAIL");
        assertThat(byId.get("freelancehunt").status()).isEqualTo(Status.SKIPPED);
        assertThat(byId.get("freelancehunt").reason()).contains("FREELANCEHUNT_TOKEN");
    }

    @Test
    void everyOtherDefaultSourceIsActiveAndOptInSourcesAreOff() {
        var byId = catalog.entries().stream().collect(java.util.stream.Collectors.toMap(SourceCatalog.Entry::id, e -> e));

        for (String id : List.of("weworkremotely", "remotive", "remoteok", "hackernews", "freelancer", "himalayas", "jobicy", "workingnomads", "flru", "dou")) {
            assertThat(byId.get(id).status()).as(id).isEqualTo(Status.ACTIVE);
        }
        assertThat(byId.get("email").status()).isEqualTo(Status.DISABLED);
        assertThat(byId.get("telegram").status()).isEqualTo(Status.DISABLED);
        assertThat(sources).extracting(JobSource::id).doesNotContain("email", "telegram");
    }
}
