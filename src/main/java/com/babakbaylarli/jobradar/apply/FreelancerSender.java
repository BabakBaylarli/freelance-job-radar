package com.babakbaylarli.jobradar.apply;

import com.babakbaylarli.jobradar.config.JobRadarProperties;
import com.babakbaylarli.jobradar.source.FreelancerSource;
import com.babakbaylarli.jobradar.source.HttpFetcher;
import com.babakbaylarli.jobradar.source.Job;
import com.babakbaylarli.jobradar.source.SourceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Places a bid through Freelancer.com's official Projects API. The calls follow Freelancer's own SDK (freelancer-sdk-python):
 * the bidder id comes from {@code GET /api/users/0.1/self/}, the bid is {@code POST /api/projects/0.1/bids/} with
 * {@code project_id, bidder_id, description, amount, period} (plus {@code milestone_percentage} only when configured), authenticated
 * with the OAuth token in the {@code Freelancer-OAuth-V1} header. A bid uses one of the account's limited monthly bids,
 * so the owner is asked to confirm first.
 */
@Component
public class FreelancerSender implements ProposalSender {

    private static final Logger log = LoggerFactory.getLogger(FreelancerSender.class);
    private static final String TOKEN_HEADER = "Freelancer-OAuth-V1";

    private final JobRadarProperties.Freelancer source;
    private final JobRadarProperties.Apply apply;
    private final HttpFetcher fetcher;
    private final ObjectMapper mapper;
    private Long bidderId;

    public FreelancerSender(JobRadarProperties properties, HttpFetcher fetcher, ObjectMapper mapper) {
        this.source = properties.sources().freelancer();
        this.apply = properties.apply();
        this.fetcher = fetcher;
        this.mapper = mapper;
    }

    @Override
    public String platform() {
        return "freelancer";
    }

    @Override
    public String route() {
        return "Freelancer.com API";
    }

    @Override
    public boolean supports(Job job) {
        return FreelancerSource.ID.equals(job.source()) && job.externalId().matches("\\d+");
    }

    @Override
    public Optional<String> unavailableReason() {
        return source.token() == null || source.token().isBlank() ? Optional.of("FREELANCER_TOKEN is not set") : Optional.empty();
    }

    @Override
    public Optional<String> confirmation(Job job) {
        return Optional.of("This uses 1 of your Freelancer.com bids (free accounts get very few per month).");
    }

    @Override
    public boolean needsBid() {
        return true;
    }

    @Override
    public SendResult send(SendRequest request) {
        if (request.bid() == null) {
            return SendResult.failed("No bid amount: this project has no budget currency to base a bid on. Set one with \"bid 500 7\".");
        }
        try {
            Map<String, String> auth = Map.of(TOKEN_HEADER, source.token().strip());
            ObjectNode body = mapper.createObjectNode();
            body.put("project_id", Long.parseLong(request.job().externalId()));
            body.put("bidder_id", bidderId(auth));
            body.put("description", request.text());
            body.put("amount", request.bid().amount());
            body.put("period", request.bid().days());
            if (apply.freelancerMilestonePercentage() != null) {
                body.put("milestone_percentage", apply.freelancerMilestonePercentage());
            }
            HttpFetcher.Response response = fetcher.postJson(source.baseUrl() + "/api/projects/0.1/bids/", mapper.writeValueAsString(body), auth);
            JsonNode json = parse(response.body());
            if (response.status() == 200 && "success".equals(json.path("status").asText())) {
                String id = json.path("result").path("id").asText("");
                log.info("event=application_sent platform=freelancer bid_id={}", id);
                return SendResult.ok(id);
            }
            String message = json.path("message").asText("HTTP " + response.status());
            String code = json.path("error_code").asText("");
            return SendResult.failed("Freelancer.com refused the bid: " + message + (code.isBlank() ? "" : " (" + code + ")"));
        } catch (SourceException e) {
            return SendResult.failed(e.getMessage());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return SendResult.failed("Could not build the Freelancer.com request");
        }
    }

    private synchronized long bidderId(Map<String, String> auth) {
        if (bidderId == null) {
            String body = fetcher.get(source.baseUrl() + "/api/users/0.1/self/", "application/json", auth);
            JsonNode id = parse(body).path("result").path("id");
            if (!id.canConvertToLong()) {
                throw new SourceException("Freelancer.com did not return your user id - is FREELANCER_TOKEN valid?");
            }
            bidderId = id.asLong();
        }
        return bidderId;
    }

    private JsonNode parse(String body) {
        try {
            return mapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new SourceException("Freelancer.com returned an unreadable answer", e);
        }
    }
}
