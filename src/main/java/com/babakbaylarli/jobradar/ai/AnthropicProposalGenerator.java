package com.babakbaylarli.jobradar.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import java.util.Locale;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Calls the Anthropic Messages API through the official Java SDK. The model name is configurable
 * ({@code jobradar.ai.model}, default {@code claude-sonnet-5-5}). Thinking is left on the model default and
 * {@code effort} is kept at {@code medium}, which is plenty for a short proposal.
 */
public class AnthropicProposalGenerator implements ProposalGenerator {

    private static final Logger log = LoggerFactory.getLogger(AnthropicProposalGenerator.class);

    private final AnthropicClient client;
    private final String model;
    private final long maxTokens;
    private final String effort;

    public AnthropicProposalGenerator(AnthropicClient client, String model, long maxTokens, String effort) {
        this.client = client;
        this.model = model;
        this.maxTokens = maxTokens;
        this.effort = effort;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String generate(PromptParts prompt) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .system(prompt.system())
                .addUserMessage(prompt.user())
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(effort.toLowerCase(Locale.ROOT))).build())
                .build();
        Message response;
        try {
            response = client.messages().create(params);
        } catch (AnthropicException e) {
            log.warn("event=ai_failed model={} error={}", model, e.getMessage());
            throw new ProposalException("The AI request failed: " + e.getMessage(), e);
        }
        if (response.stopReason().filter(StopReason.REFUSAL::equals).isPresent()) {
            throw new ProposalException("The model declined to write this proposal.");
        }
        String text = response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(TextBlock::text)
                .collect(Collectors.joining())
                .strip();
        if (text.isEmpty()) {
            throw new ProposalException("The model returned no text"
                    + response.stopReason().filter(StopReason.MAX_TOKENS::equals).map(r -> " (hit the max-tokens limit)").orElse(""));
        }
        log.info("event=ai_ok model={} input_tokens={} output_tokens={}",
                model, response.usage().inputTokens(), response.usage().outputTokens());
        return text;
    }
}
