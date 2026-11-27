package com.babakbaylarli.jobradar.apply;

import static org.assertj.core.api.Assertions.assertThat;

import com.babakbaylarli.jobradar.TestSupport;
import com.babakbaylarli.jobradar.config.JobRadarProperties;
import com.babakbaylarli.jobradar.source.Job;
import com.babakbaylarli.jobradar.source.telegram.DirectMessageException;
import com.babakbaylarli.jobradar.source.telegram.TdLightSession;
import com.babakbaylarli.jobradar.source.telegram.TelegramDirectMessenger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TelegramDmSenderTest {

    static class FakeMessenger implements TelegramDirectMessenger {
        final List<String> sent = new ArrayList<>();
        String failWith;

        @Override
        public String send(String username, String text) throws DirectMessageException {
            if (failWith != null) {
                throw new DirectMessageException(failWith);
            }
            sent.add(username + ": " + text);
            return "5521";
        }
    }

    private static TdLightSession session(boolean credentials) {
        var channels = new JobRadarProperties.TelegramChannels(false, Duration.ofMinutes(30), credentials ? "1" : null, credentials ? "h" : null,
                credentials ? "+1" : null, "/tmp/x", List.of(), 50);
        JobRadarProperties base = TestSupport.props("http://unused");
        var s = base.sources();
        return new TdLightSession(new JobRadarProperties(base.telegram(), base.ai(), base.matching(), base.notifications(), base.http(),
                new JobRadarProperties.Sources(s.weWorkRemotely(), s.remotive(), s.remoteOk(), s.hackerNews(), s.freelancer(), s.himalayas(), s.jobicy(),
                        s.workingNomads(), s.flRu(), s.hh(), s.freelancehunt(), s.dou(), s.emailAlerts(), channels), base.profilePath(), base.apply()));
    }

    private static Job post(String source, String text) {
        return new Job(source, "1042", "Нужен Java-разработчик", "", "https://t.me/ru_freelance/1042", List.of(), text, null, null);
    }

    @Test
    void sendsExactlyOneMessageToTheContactNamedInTheChannelPost() {
        FakeMessenger messenger = new FakeMessenger();
        TelegramDmSender sender = new TelegramDmSender(messenger, session(true));
        Job job = post("telegram:ru_freelance", "Нужен разработчик. Писать @ivan_hr, канал @ru_freelance");

        assertThat(sender.supports(job)).isTrue();
        assertThat(sender.recipient(job)).contains("@ivan_hr");

        SendResult result = sender.send(new SendRequest(job, "Здравствуйте! Готов помочь.", null, "@ivan_hr"));

        assertThat(result.ok()).isTrue();
        assertThat(result.responseId()).isEqualTo("5521");
        assertThat(messenger.sent).containsExactly("ivan_hr: Здравствуйте! Готов помочь.");
    }

    @Test
    void onlyAppliesToTelegramPostsWithAHumanContact() {
        TelegramDmSender sender = new TelegramDmSender(new FakeMessenger(), session(true));

        assertThat(sender.supports(post("dou", "contact @ivan_hr"))).isFalse();                                  // not a Telegram post
        assertThat(sender.supports(post("telegram:jobs", "Contact our bot @jobs_helper_bot"))).isFalse();        // bots are never messaged
        assertThat(sender.supports(post("telegram:jobs", "no contact here"))).isFalse();
        assertThat(sender.supports(post("telegram:jobs", "ask @jobs"))).isFalse();                               // the channel itself
    }

    @Test
    void refusalsFromTelegramBecomeVisibleFailures() {
        FakeMessenger messenger = new FakeMessenger();
        messenger.failWith = "Telegram asks to wait before sending again (flood wait): FLOOD_WAIT_30";
        TelegramDmSender sender = new TelegramDmSender(messenger, session(true));

        SendResult result = sender.send(new SendRequest(post("telegram:jobs", "x"), "text", null, "@ivan_hr"));

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).contains("flood wait");
    }

    @Test
    void needsTheTelegramClientCredentialsAndARecipient() {
        assertThat(new TelegramDmSender(new FakeMessenger(), session(false)).unavailableReason()).hasValueSatisfying(r -> assertThat(r).contains("TELEGRAM_API_ID"));
        assertThat(new TelegramDmSender(new FakeMessenger(), session(true)).unavailableReason()).isEmpty();
        assertThat(new TelegramDmSender(new FakeMessenger(), session(true)).send(new SendRequest(post("telegram:j", "x"), "t", null, null)).ok()).isFalse();
    }
}
