package com.babakbaylarli.jobradar.source;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.babakbaylarli.jobradar.TestSupport;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class HttpFetcherTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private HttpFetcher fetcher() {
        return new HttpFetcher(TestSupport.props(wm.baseUrl()));
    }

    @Test
    void sendsConfiguredUserAgent() {
        wm.stubFor(get("/ua").willReturn(aResponse().withStatus(200).withBody("ok")));

        assertThat(fetcher().get(wm.baseUrl() + "/ua", "text/plain")).isEqualTo("ok");

        wm.verify(getRequestedFor(urlEqualTo("/ua"))
                .withHeader("User-Agent", equalTo("test-agent/1.0 (+https://example.test)"))
                .withHeader("Accept", equalTo("text/plain")));
    }

    @Test
    void retriesTransientServerErrorsWithBackoff() {
        wm.stubFor(get("/flaky").inScenario("flaky").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("second"));
        wm.stubFor(get("/flaky").inScenario("flaky").whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(500)).willSetStateTo("third"));
        wm.stubFor(get("/flaky").inScenario("flaky").whenScenarioStateIs("third")
                .willReturn(aResponse().withStatus(200).withBody("finally")));

        assertThat(fetcher().get(wm.baseUrl() + "/flaky", "*/*")).isEqualTo("finally");
        wm.verify(3, getRequestedFor(urlEqualTo("/flaky")));
    }

    @Test
    void givesUpAfterMaxAttempts() {
        wm.stubFor(get("/down").willReturn(aResponse().withStatus(502)));

        assertThatThrownBy(() -> fetcher().get(wm.baseUrl() + "/down", "*/*"))
                .isInstanceOf(SourceException.class)
                .hasMessageContaining("after 3 attempts");
        wm.verify(3, getRequestedFor(urlEqualTo("/down")));
    }

    @Test
    void doesNotRetryRateLimitOrClientErrors() {
        wm.stubFor(get("/limited").willReturn(aResponse().withStatus(429).withHeader("Retry-After", "3600")));

        assertThatThrownBy(() -> fetcher().get(wm.baseUrl() + "/limited", "*/*"))
                .isInstanceOf(SourceException.class)
                .hasMessageContaining("429");
        wm.verify(1, getRequestedFor(urlEqualTo("/limited")));
    }

    @Test
    void emptyBodyIsAnError() {
        wm.stubFor(get("/empty").willReturn(aResponse().withStatus(200).withBody("")));

        assertThatThrownBy(() -> fetcher().get(wm.baseUrl() + "/empty", "*/*")).isInstanceOf(SourceException.class);
    }
}
