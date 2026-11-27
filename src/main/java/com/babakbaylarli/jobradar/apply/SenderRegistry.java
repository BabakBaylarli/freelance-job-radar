package com.babakbaylarli.jobradar.apply;

import com.babakbaylarli.jobradar.source.Job;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Picks the route for a job: a ready sender, or none - then the owner uses "Copy &amp; open". */
@Component
public class SenderRegistry {

    private final List<ProposalSender> senders;

    public SenderRegistry(List<ProposalSender> senders) {
        this.senders = senders;
    }

    public Optional<ProposalSender> readySenderFor(Job job) {
        return senders.stream().filter(s -> s.supports(job) && s.unavailableReason().isEmpty()).findFirst();
    }

    /** A sender that fits the job but cannot run yet (e.g. missing token) - to tell the owner what to configure. */
    public Optional<String> missingSetupFor(Job job) {
        return senders.stream().filter(s -> s.supports(job)).findFirst().flatMap(ProposalSender::unavailableReason);
    }

    public Optional<ProposalSender> byPlatform(String platform) {
        return senders.stream().filter(s -> s.platform().equals(platform)).findFirst();
    }
}
