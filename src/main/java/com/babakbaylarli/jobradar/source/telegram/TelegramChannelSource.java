package com.babakbaylarli.jobradar.source.telegram;

import com.babakbaylarli.jobradar.config.JobRadarProperties;
import com.babakbaylarli.jobradar.config.JobRadarProperties.Channel;
import com.babakbaylarli.jobradar.source.BudgetText;
import com.babakbaylarli.jobradar.source.HtmlText;
import com.babakbaylarli.jobradar.source.Job;
import com.babakbaylarli.jobradar.source.JobSource;
import com.babakbaylarli.jobradar.source.Market;
import com.babakbaylarli.jobradar.source.SourceException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Every new post of the public channels listed in {@code config/channels.yml} becomes a job (text, link to the post,
 * channel name). Off by default; skipped with a reason when the API credentials or the channel list are missing. The
 * source only reads: it never joins, posts or messages anyone.
 */
@Component
@ConditionalOnProperty(name = "jobradar.sources.telegram-channels.enabled", havingValue = "true")
public class TelegramChannelSource implements JobSource {

    public static final String ID = "telegram";
    private static final int MIN_POST_LENGTH = 30;
    private static final long PAUSE_BETWEEN_CHANNELS_MS = 1500; // be gentle with Telegram's flood limits
    private static final Logger log = LoggerFactory.getLogger(TelegramChannelSource.class);

    private final JobRadarProperties.TelegramChannels config;
    private final ChannelReader reader;
    private final long pauseMillis;

    @org.springframework.beans.factory.annotation.Autowired
    public TelegramChannelSource(JobRadarProperties properties, ChannelReader reader) {
        this(properties, reader, PAUSE_BETWEEN_CHANNELS_MS);
    }

    TelegramChannelSource(JobRadarProperties properties, ChannelReader reader, long pauseMillis) {
        this.config = properties.sources().telegramChannels();
        this.reader = reader;
        this.pauseMillis = pauseMillis;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Telegram channels";
    }

    @Override
    public Duration pollInterval() {
        return config.pollInterval();
    }

    @Override
    public Optional<String> unavailableReason() {
        if (!config.credentialsPresent()) {
            return Optional.of("TELEGRAM_API_ID / TELEGRAM_API_HASH / TELEGRAM_PHONE are not set");
        }
        if (config.channels().stream().noneMatch(c -> !c.username().isBlank())) {
            return Optional.of("no channels listed in config/channels.yml");
        }
        return Optional.empty();
    }

    @Override
    public List<Job> fetch() {
        List<Job> jobs = new ArrayList<>();
        SourceException lastFailure = null;
        int failed = 0;
        int attempted = 0;
        for (Channel channel : config.channels()) {
            if (channel.username().isBlank()) {
                continue;
            }
            if (attempted++ > 0) {
                pause();
            }
            try {
                for (ChannelPost post : reader.latestPosts(channel.username(), config.maxPostsPerChannel())) {
                    toJob(channel, post).ifPresent(jobs::add);
                }
            } catch (ChannelReaderException e) {
                failed++;
                lastFailure = new SourceException(e.getMessage(), e);
                log.warn("event=telegram_channel_failed channel={} error={}", channel.username(), e.getMessage());
            }
        }
        if (attempted > 0 && failed == attempted) {
            throw lastFailure;
        }
        return jobs;
    }

    static Optional<Job> toJob(Channel channel, ChannelPost post) {
        if (post.text().strip().length() < MIN_POST_LENGTH) {
            return Optional.empty(); // "+1", stickers with a word, emoji-only posts
        }
        String text = post.text().strip();
        return Optional.of(new Job("telegram:" + channel.username(), String.valueOf(post.messageId()), HtmlText.firstLine(text, 160), "",
                post.link(), List.of(channel.username()), HtmlText.truncate(text, 4000), null, post.date(), channel.market(), channel.type(),
                BudgetText.parse(text, defaultCurrency(channel.market()))));
    }

    private static String defaultCurrency(Market market) {
        return switch (market) {
            case RU -> "RUB";
            case UA -> "UAH";
            case AZ -> "AZN";
            default -> "USD";
        };
    }

    private void pause() {
        try {
            Thread.sleep(pauseMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        reader.close();
    }
}
