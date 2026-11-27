package com.babakbaylarli.jobradar.ai;

/** Turns a prompt into proposal text. The only implementation that talks to the network is Anthropic's. */
public interface ProposalGenerator {

    /** {@code false} when no API key is configured: the bot keeps working and explains that AI is disabled. */
    boolean available();

    String generate(PromptParts prompt) throws ProposalException;

    /** Used when {@code ANTHROPIC_API_KEY} is not set. */
    static ProposalGenerator disabled() {
        return new ProposalGenerator() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public String generate(PromptParts prompt) {
                throw new ProposalException("AI proposals are disabled (no ANTHROPIC_API_KEY configured)");
            }
        };
    }
}
