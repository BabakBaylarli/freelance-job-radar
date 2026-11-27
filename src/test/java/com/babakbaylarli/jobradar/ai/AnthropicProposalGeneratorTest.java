package com.babakbaylarli.jobradar.ai;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class AnthropicProposalGeneratorTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private static final PromptParts PROMPT = new PromptParts("SYSTEM RULES + <profile>Java dev</profile>", "<job>Title: X</job>");

    private AnthropicProposalGenerator generator() {
        AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey("test-key")
                .baseUrl(wm.baseUrl())
                .maxRetries(0)
                .build();
        return new AnthropicProposalGenerator(client, "claude-sonnet-5-5", 2000, "medium");
    }

    private static String message(String stopReason, String contentJson) {
        return """
                {"id":"msg_test","type":"message","role":"assistant","model":"claude-sonnet-5-5",
                 "content":%s,"stop_reason":"%s","stop_sequence":null,
                 "usage":{"input_tokens":321,"output_tokens":123}}
                """.formatted(contentJson, stopReason);
    }

    @Test
    void sendsConfiguredModelPromptAndEffortAndReturnsTheText() {
        wm.stubFor(post(urlEqualTo("/v1/messages")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(message("end_turn", "[{\"type\":\"text\",\"text\":\"  Hi Acme, your ledger rebuild sounds fun. Question?  \"}]"))));

        String text = generator().generate(PROMPT);

        assertThat(text).isEqualTo("Hi Acme, your ledger rebuild sounds fun. Question?");
        wm.verify(postRequestedFor(urlEqualTo("/v1/messages"))
                .withHeader("Authorization", equalTo("Bearer test-key")) // how SDK 3.x sends the API key
                .withHeader("anthropic-version", com.github.tomakehurst.wiremock.client.WireMock.matching(".+"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("claude-sonnet-5-5")))
                .withRequestBody(matchingJsonPath("$.max_tokens", equalTo("2000")))
                .withRequestBody(matchingJsonPath("$.system", equalTo(PROMPT.system())))
                .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("user")))
                .withRequestBody(matchingJsonPath("$.messages[0].content", equalTo(PROMPT.user())))
                .withRequestBody(matchingJsonPath("$.output_config.effort", equalTo("medium"))));
    }

    @Test
    void doesNotSendSamplingParametersOrThinkingBudget() {
        wm.stubFor(post(urlEqualTo("/v1/messages")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(message("end_turn", "[{\"type\":\"text\",\"text\":\"ok\"}]"))));

        generator().generate(PROMPT);

        wm.verify(postRequestedFor(urlEqualTo("/v1/messages"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.notMatching(".*temperature.*"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.notMatching(".*budget_tokens.*")));
    }

    @Test
    void joinsMultipleTextBlocksAndIgnoresThinkingBlocks() {
        wm.stubFor(post(urlEqualTo("/v1/messages")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(message("end_turn", "[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"sig\"},"
                        + "{\"type\":\"text\",\"text\":\"Part one. \"},{\"type\":\"text\",\"text\":\"Part two?\"}]"))));

        assertThat(generator().generate(PROMPT)).isEqualTo("Part one. Part two?");
    }

    @Test
    void apiErrorBecomesAProposalException() {
        wm.stubFor(post(urlEqualTo("/v1/messages")).willReturn(aResponse().withStatus(401)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}")));

        assertThatThrownBy(() -> generator().generate(PROMPT))
                .isInstanceOf(ProposalException.class)
                .hasMessageContaining("AI request failed");
    }

    @Test
    void refusalIsReportedClearly() {
        wm.stubFor(post(urlEqualTo("/v1/messages")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(message("refusal", "[]"))));

        assertThatThrownBy(() -> generator().generate(PROMPT))
                .isInstanceOf(ProposalException.class)
                .hasMessageContaining("declined");
    }

    @Test
    void emptyAnswerHitTheTokenLimitIsExplained() {
        wm.stubFor(post(urlEqualTo("/v1/messages")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(message("max_tokens", "[]"))));

        assertThatThrownBy(() -> generator().generate(PROMPT))
                .isInstanceOf(ProposalException.class)
                .hasMessageContaining("max-tokens");
    }
}
