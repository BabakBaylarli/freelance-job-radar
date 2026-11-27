package com.babakbaylarli.jobradar;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.babakbaylarli.jobradar.ai.ProposalGenerator;
import com.babakbaylarli.jobradar.bot.NoopTelegramGateway;
import com.babakbaylarli.jobradar.bot.TelegramGateway;
import com.babakbaylarli.jobradar.config.JobRadarProperties;
import com.babakbaylarli.jobradar.persistence.JobEntity;
import com.babakbaylarli.jobradar.persistence.JobRepository;
import com.babakbaylarli.jobradar.persistence.SourceStateRepository;
import com.babakbaylarli.jobradar.source.JobSource;
import com.babakbaylarli.jobradar.source.SourcePoller;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots the whole application against real Postgres with NO Telegram token and NO Anthropic key (the situation of a
 * first local start), and runs a fake Remotive through poller -> ingest -> scoring -> database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "jobradar.telegram.bot-token=",
        "jobradar.ai.api-key=",
        "jobradar.sources.we-work-remotely.enabled=false",
        "jobradar.sources.remote-ok.enabled=false",
        "jobradar.sources.hacker-news.enabled=false",
        "jobradar.sources.freelancer.enabled=false",
        "jobradar.sources.himalayas.enabled=false",
        "jobradar.sources.jobicy.enabled=false",
        "jobradar.sources.working-nomads.enabled=false",
        "jobradar.sources.fl-ru.enabled=false",
        "jobradar.sources.hh.enabled=false",
        "jobradar.sources.freelancehunt.enabled=false",
        "jobradar.sources.dou.enabled=false",
        "jobradar.poll-tick-initial-delay=PT1H",
        "jobradar.flush-initial-delay=PT1H"})
@Testcontainers
class ApplicationStartupTest {

    @RegisterExtension
    static WireMockExtension remotiveApi = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void remotiveBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("jobradar.sources.remotive.base-url", remotiveApi::baseUrl);
    }

    @Autowired TestRestTemplate rest;
    @Autowired TelegramGateway gateway;
    @Autowired ProposalGenerator generator;
    @Autowired JobRadarProperties properties;
    @Autowired List<JobSource> sources;
    @Autowired SourcePoller poller;
    @Autowired JobRepository jobs;
    @Autowired SourceStateRepository sourceStates;

    @Test
    void startsWithoutAnyKeysAndHealthIsUp() {
        var health = rest.getForEntity("/actuator/health", String.class);

        assertThat(health.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(health.getBody()).contains("\"status\":\"UP\"");
        assertThat(gateway).isInstanceOf(NoopTelegramGateway.class);
        assertThat(gateway.enabled()).isFalse();
        assertThat(generator.available()).isFalse();
    }

    @Test
    void sourcesCanBeSwitchedOffIndividually() {
        assertThat(sources).extracting(JobSource::id).containsExactly("remotive");
    }

    @Test
    void keywordsFileIsBoundFromConfig() {
        assertThat(properties.matching().include()).isNotEmpty()
                .anySatisfy(k -> {
                    assertThat(k.term()).isEqualTo("java");
                    assertThat(k.weight()).isEqualTo(3);
                });
        assertThat(properties.matching().exclude()).contains("onsite");
        assertThat(properties.notifications().quietHours().describe()).isEqualTo("00:00-08:00 Asia/Baku");
        assertThat(properties.ai().model()).isEqualTo("claude-sonnet-5-5");
    }

    @Test
    void jobsFlowFromTheFeedThroughScoringIntoPostgresWithoutDuplicates() {
        remotiveApi.stubFor(get(urlPathEqualTo("/api/remote-jobs"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(TestSupport.sample("remotive.json"))));
        JobSource remotive = sources.get(0);

        poller.pollNow(remotive);

        List<JobEntity> stored = jobs.findAll();
        assertThat(stored).hasSize(3);
        JobEntity java = stored.stream().filter(j -> j.getExternalId().equals("1920001")).findFirst().orElseThrow();
        assertThat(java.isMatched()).isTrue();
        assertThat(java.getMatchedKeywords()).contains("java", "spring", "kafka", "payments");
        assertThat(java.getScore()).isGreaterThanOrEqualTo(10);
        assertThat(stored.stream().filter(JobEntity::isMatched)).hasSize(2); // the React job does not match
        var state = sourceStates.findById("remotive").orElseThrow();
        assertThat(state.getLastStatus()).isEqualTo("OK");
        assertThat(state.getLastFetched()).isEqualTo(3);
        assertThat(state.getLastNew()).isEqualTo(3);

        poller.pollNow(remotive); // the same feed again: nothing new

        assertThat(jobs.count()).isEqualTo(3);
        assertThat(sourceStates.findById("remotive").orElseThrow().getLastNew()).isZero();
    }
}
