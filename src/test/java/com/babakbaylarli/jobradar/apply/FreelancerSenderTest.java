package com.babakbaylarli.jobradar.apply;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.babakbaylarli.jobradar.TestSupport;
import com.babakbaylarli.jobradar.config.JobRadarProperties;
import com.babakbaylarli.jobradar.source.HttpFetcher;
import com.babakbaylarli.jobradar.source.Job;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class FreelancerSenderTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private static final Job JOB = new Job("freelancer", "40000001", "Spring Boot payment microservice", "", "https://www.freelancer.example/projects/java/x",
            List.of(), "desc", null, null);
    private static final Bid BID = new Bid(new BigDecimal("1000"), "USD", 7, null);

    private FreelancerSender sender(boolean token, Integer milestone) {
        JobRadarProperties base = token ? TestSupport.props(wm.baseUrl()) : TestSupport.propsWithoutSecrets(wm.baseUrl());
        JobRadarProperties props = TestSupport.withApply(base, new JobRadarProperties.Apply(10, Map.of(), Duration.ofMinutes(2), Map.of(), Map.of(), 7,
                List.of(), "config/cv.pdf", ZoneId.of("Asia/Baku"), new JobRadarProperties.Smtp("localhost", 25, false, false), milestone));
        return new FreelancerSender(props, new HttpFetcher(props), new ObjectMapper());
    }

    private void stubSelf() {
        wm.stubFor(get(urlEqualTo("/api/users/0.1/self/")).willReturn(aResponse().withBody("{\"status\":\"success\",\"result\":{\"id\":123456,\"username\":\"me\"}}")));
    }

    @Test
    void placesTheBidThroughTheDocumentedEndpointWithTheOfficialPayload() {
        stubSelf();
        wm.stubFor(post(urlEqualTo("/api/projects/0.1/bids/")).willReturn(aResponse().withStatus(200)
                .withBody("{\"status\":\"success\",\"result\":{\"id\":987654321,\"project_id\":40000001}}")));

        SendResult result = sender(true, null).send(new SendRequest(JOB, "My proposal text", BID, null));

        assertThat(result.ok()).isTrue();
        assertThat(result.responseId()).isEqualTo("987654321");
        wm.verify(getRequestedFor(urlEqualTo("/api/users/0.1/self/")).withHeader("Freelancer-OAuth-V1", equalTo("fl-token")));
        wm.verify(1, postRequestedFor(urlEqualTo("/api/projects/0.1/bids/"))
                .withHeader("Freelancer-OAuth-V1", equalTo("fl-token"))
                .withRequestBody(equalToJson("{\"project_id\":40000001,\"bidder_id\":123456,\"description\":\"My proposal text\",\"amount\":1000,\"period\":7}")));
    }

    @Test
    void milestonePercentageIsOnlySentWhenConfigured() {
        stubSelf();
        wm.stubFor(post(urlEqualTo("/api/projects/0.1/bids/")).willReturn(aResponse().withBody("{\"status\":\"success\",\"result\":{\"id\":1}}")));

        sender(true, 30).send(new SendRequest(JOB, "text", BID, null));

        wm.verify(postRequestedFor(urlEqualTo("/api/projects/0.1/bids/"))
                .withRequestBody(equalToJson("{\"project_id\":40000001,\"bidder_id\":123456,\"description\":\"text\",\"amount\":1000,\"period\":7,\"milestone_percentage\":30}")));
    }

    @Test
    void anErrorAnswerBecomesAVisibleFailureWithFreelancersReason() {
        stubSelf();
        wm.stubFor(post(urlEqualTo("/api/projects/0.1/bids/")).willReturn(aResponse().withStatus(403)
                .withBody("{\"status\":\"error\",\"message\":\"You have no bids left\",\"error_code\":\"BidsLimitExceeded\",\"request_id\":\"r1\"}")));

        SendResult result = sender(true, null).send(new SendRequest(JOB, "text", BID, null));

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).contains("You have no bids left").contains("BidsLimitExceeded");
    }

    @Test
    void aServerErrorIsNeverRetriedBecauseARetryCouldPlaceTheBidTwice() {
        stubSelf();
        wm.stubFor(post(urlEqualTo("/api/projects/0.1/bids/")).willReturn(aResponse().withStatus(500).withBody("oops")));

        SendResult result = sender(true, null).send(new SendRequest(JOB, "text", BID, null));

        assertThat(result.ok()).isFalse();
        wm.verify(1, postRequestedFor(urlEqualTo("/api/projects/0.1/bids/")));
    }

    @Test
    void anInvalidTokenIsReportedClearly() {
        wm.stubFor(get(urlEqualTo("/api/users/0.1/self/")).willReturn(aResponse().withStatus(401).withBody("{\"status\":\"error\"}")));

        SendResult result = sender(true, null).send(new SendRequest(JOB, "text", BID, null));

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).contains("401");
    }

    @Test
    void refusesToSendWithoutABid() {
        SendResult result = sender(true, null).send(new SendRequest(JOB, "text", null, null));

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).contains("bid");
    }

    @Test
    void appliesOnlyToFreelancerProjectsAndNeedsTheToken() {
        FreelancerSender withToken = sender(true, null);

        assertThat(withToken.supports(JOB)).isTrue();
        assertThat(withToken.supports(new Job("remotive", "1", "t", "", "https://x.test/1", List.of(), "", null, null))).isFalse();
        assertThat(withToken.supports(new Job("freelancer", "abc", "t", "", "https://x.test/1", List.of(), "", null, null))).isFalse();
        assertThat(withToken.unavailableReason()).isEmpty();
        assertThat(sender(false, null).unavailableReason()).hasValueSatisfying(r -> assertThat(r).contains("FREELANCER_TOKEN"));
        assertThat(withToken.needsBid()).isTrue();
        assertThat(withToken.confirmation(JOB)).hasValueSatisfying(c -> assertThat(c).contains("1 of your Freelancer.com bids"));
    }
}
