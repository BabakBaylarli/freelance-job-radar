package com.babakbaylarli.jobradar.ai;

import com.babakbaylarli.jobradar.source.Job;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Orchestrates a proposal draft: profile + job -> prompt -> model. Never throws; reports an outcome instead. */
@Service
public class ProposalService {

    private static final Logger log = LoggerFactory.getLogger(ProposalService.class);

    public sealed interface Outcome {
        /** A finished draft. */
        record Drafted(String text) implements Outcome {}

        /** No API key: the bot explains how to enable AI. */
        record Disabled() implements Outcome {}

        /** Something went wrong; {@code message} is safe to show to the owner. */
        record Failed(String message) implements Outcome {}
    }

    private final ProposalGenerator generator;
    private final ProposalPromptBuilder promptBuilder;
    private final ProfileLoader profileLoader;

    public ProposalService(ProposalGenerator generator, ProposalPromptBuilder promptBuilder, ProfileLoader profileLoader) {
        this.generator = generator;
        this.promptBuilder = promptBuilder;
        this.profileLoader = profileLoader;
    }

    public boolean aiEnabled() {
        return generator.available();
    }

    public Outcome draft(Job job) {
        if (!generator.available()) {
            return new Outcome.Disabled();
        }
        Optional<String> profile = profileLoader.load();
        if (profile.isEmpty()) {
            return new Outcome.Failed("I can't find your profile at %s (or it is empty). Create it from config/profile.example.md "
                    .formatted(profileLoader.path()) + "- proposals are only ever based on facts you put there.");
        }
        try {
            return new Outcome.Drafted(generator.generate(promptBuilder.build(job, profile.get())));
        } catch (ProposalException e) {
            return new Outcome.Failed(e.getMessage());
        } catch (RuntimeException e) {
            log.error("event=proposal_error job={}", job.url(), e);
            return new Outcome.Failed("Unexpected error while drafting the proposal.");
        }
    }
}
