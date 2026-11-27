package com.babakbaylarli.jobradar.apply;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContactFinderTest {

    @Test
    void findsTheFirstRealEmailAndSkipsNoReplyAndOwnAddress() {
        String text = "Apply: noreply@jobs.example, or write to Hiring.Team@Example.COM (cc me@mine.test). Logo: banner@2x.png";

        assertThat(ContactFinder.email(text, "me@mine.test")).contains("hiring.team@example.com");
        assertThat(ContactFinder.email("only noreply@x.test and donotreply@y.test", null)).isEmpty();
        assertThat(ContactFinder.email("my own me@mine.test", "ME@mine.test")).isEmpty();
        assertThat(ContactFinder.email(null, null)).isEmpty();
    }

    @Test
    void trailingPunctuationIsNotPartOfTheAddress() {
        assertThat(ContactFinder.email("Send your CV to jobs@acme.io.", null)).contains("jobs@acme.io");
    }

    @Test
    void findsATelegramUsernameButNotTheChannelItselfOrBots() {
        String text = "Нужен Java-разработчик. Писать @freelance_channel или @ivan_hr, бот: @jobs_helper_bot";

        assertThat(ContactFinder.telegramUsername(text, "freelance_channel")).contains("ivan_hr");
        assertThat(ContactFinder.telegramUsername("пишите @only_bot", "x")).isEmpty();
        assertThat(ContactFinder.telegramUsername("mail me at a@b.com", "x")).isEmpty(); // an e-mail is not a username
        assertThat(ContactFinder.telegramUsername("@abc", "x")).isEmpty();               // too short to be a real username
    }
}
