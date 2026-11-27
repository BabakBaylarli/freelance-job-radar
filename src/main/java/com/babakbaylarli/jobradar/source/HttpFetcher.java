package com.babakbaylarli.jobradar.source;

import com.babakbaylarli.jobradar.config.JobRadarProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Polite HTTP GET for job feeds: sends a descriptive User-Agent, uses timeouts, retries transient
 * failures (I/O errors, 5xx) with exponential backoff and does <em>not</em> retry 4xx - in particular
 * a 429 is treated as "back off until the next poll" instead of hammering the source.
 */
@Component
public class HttpFetcher {

    private static final Logger log = LoggerFactory.getLogger(HttpFetcher.class);

    private final RestClient client;
    private final int maxAttempts;
    private final Duration initialBackoff;

    public HttpFetcher(JobRadarProperties properties) {
        JobRadarProperties.Http http = properties.http();
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(http.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(http.readTimeout());
        this.client = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.USER_AGENT, http.userAgent())
                .build();
        this.maxAttempts = Math.max(1, http.maxAttempts());
        this.initialBackoff = http.initialBackoff();
    }

    public String get(String url, String accept) {
        return get(url, accept, Map.of());
    }

    /** GET with extra request headers (tokens, vendor-specific user agents). */
    public String get(String url, String accept, Map<String, String> headers) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                String body = client.get()
                        .uri(java.net.URI.create(url))
                        .header(HttpHeaders.ACCEPT, accept)
                        .headers(h -> headers.forEach(h::set))
                        .retrieve()
                        .body(String.class);
                if (body == null || body.isBlank()) {
                    throw new SourceException("Empty response from " + url);
                }
                return body;
            } catch (HttpClientErrorException e) {
                // 4xx (incl. 429 rate limit): retrying immediately would only make things worse.
                throw new SourceException("HTTP %d from %s".formatted(e.getStatusCode().value(), url), e);
            } catch (HttpServerErrorException | org.springframework.web.client.ResourceAccessException e) {
                last = e;
            } catch (RestClientException e) {
                throw new SourceException("Request to " + url + " failed: " + e.getMessage(), e);
            }
            if (attempt < maxAttempts) {
                long delayMs = initialBackoff.toMillis() * (1L << (attempt - 1));
                log.warn("Fetch attempt {}/{} for {} failed ({}); retrying in {} ms",
                        attempt, maxAttempts, url, describe(last), delayMs);
                sleep(delayMs);
            }
        }
        throw new SourceException("Giving up on %s after %d attempts: %s".formatted(url, maxAttempts, describe(last)), last);
    }

    /** Root cause as "ClassName: message" - connection errors often have no message of their own. */
    static String describe(Throwable error) {
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(error);
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceException("Interrupted while backing off", e);
        }
    }
}
