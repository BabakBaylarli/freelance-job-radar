package com.babakbaylarli.jobradar.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.babakbaylarli.jobradar.config.JobRadarProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    /** The real generator when an API key is present, otherwise a disabled one - the app starts either way. */
    @Bean
    ProposalGenerator proposalGenerator(JobRadarProperties properties) {
        JobRadarProperties.Ai ai = properties.ai();
        if (!ai.enabled()) {
            log.warn("ANTHROPIC_API_KEY is not set: AI proposals are DISABLED. The bot works normally and the "
                    + "'Draft proposal' button will explain how to enable AI.");
            return ProposalGenerator.disabled();
        }
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                .apiKey(ai.apiKey())
                .timeout(ai.timeout())
                .maxRetries(2);
        if (ai.baseUrl() != null && !ai.baseUrl().isBlank()) {
            builder.baseUrl(ai.baseUrl());
        }
        AnthropicClient client = builder.build();
        log.info("AI proposals enabled with model {}", ai.model());
        return new AnthropicProposalGenerator(client, ai.model(), ai.maxTokens(), ai.effort());
    }
}
