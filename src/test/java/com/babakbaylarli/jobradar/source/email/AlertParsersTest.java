package com.babakbaylarli.jobradar.source.email;

import static org.assertj.core.api.Assertions.assertThat;

import com.babakbaylarli.jobradar.TestSupport;
import com.babakbaylarli.jobradar.source.Budget;
import com.babakbaylarli.jobradar.source.Job;
import com.babakbaylarli.jobradar.source.JobType;
import com.babakbaylarli.jobradar.source.Market;
import java.util.List;
import org.junit.jupiter.api.Test;

class AlertParsersTest {

    private static List<Job> parse(String sample) throws Exception {
        EmailMessageData mail = EmailMessageData.from(TestSupport.sampleMail(sample));
        AlertParser parser = AlertParsers.all().stream().filter(p -> p.supports(mail)).findFirst().orElseThrow();
        return parser.parse(mail);
    }

    @Test
    void upworkAlertGivesTwoJobsWithCleanUrlsBudgetsAndWrappedRedirectsResolved() throws Exception {
        List<Job> jobs = parse("upwork-alert");

        assertThat(jobs).hasSize(2);
        Job hourly = jobs.get(0);
        assertThat(hourly.source()).isEqualTo("email:upwork");
        assertThat(hourly.externalId()).isEqualTo("~01abc123def4567890");
        assertThat(hourly.url()).isEqualTo("https://www.upwork.com/jobs/~01abc123def4567890"); // tracking parameters removed
        assertThat(hourly.title()).isEqualTo("Java Spring Boot developer for payment API");
        assertThat(hourly.budget()).isEqualTo(Budget.of(30, 60, "USD", true));
        assertThat(hourly.description()).contains("extend our payments API").contains("Kafka experience preferred").doesNotContain("View job");
        assertThat(hourly.type()).isEqualTo(JobType.FREELANCE_PROJECT);
        assertThat(hourly.market()).isEqualTo(Market.INTERNATIONAL);
        Job fixed = jobs.get(1);
        assertThat(fixed.url()).isEqualTo("https://www.upwork.com/jobs/~01fff999eee8887776"); // found inside a click-tracking redirect
        assertThat(fixed.budget()).isEqualTo(Budget.of(1200, null, "USD", false));
    }

    @Test
    void djinniDigestGivesVacanciesWithCompanyAndSalaryForTheUkrainianMarket() throws Exception {
        List<Job> jobs = parse("djinni-digest");

        assertThat(jobs).hasSize(2);
        Job first = jobs.get(0);
        assertThat(first.source()).isEqualTo("email:djinni");
        assertThat(first.url()).isEqualTo("https://djinni.co/jobs/712345/");
        assertThat(first.title()).isEqualTo("Senior Java Developer");
        assertThat(first.company()).isEqualTo("Example Software · Remote, Ukraine");
        assertThat(first.budget()).isEqualTo(Budget.of(4000, 5500, "USD", false));
        assertThat(first.market()).isEqualTo(Market.UA);
        assertThat(first.type()).isEqualTo(JobType.VACANCY);
        assertThat(first.description()).contains("Spring Boot, Kafka, PostgreSQL");
        assertThat(jobs.get(1).budget()).isEqualTo(Budget.of(2500, null, "USD", false));
    }

    @Test
    void linkedinAlertUsesTheTitleLinkNotTheLogoLinkAndIgnoresSeeAllJobs() throws Exception {
        List<Job> jobs = parse("linkedin-alert");

        assertThat(jobs).hasSize(2);
        assertThat(jobs.get(0).title()).isEqualTo("Backend Engineer (Java)");
        assertThat(jobs.get(0).company()).isEqualTo("Example Bank");
        assertThat(jobs.get(0).url()).isEqualTo("https://www.linkedin.com/jobs/view/3912345678");
        assertThat(jobs.get(0).description()).contains("Warsaw, Poland (Remote)");
        assertThat(jobs.get(1).externalId()).isEqualTo("3912399999");
        assertThat(jobs.get(1).company()).isEqualTo("Globex Payments");
    }

    @Test
    void freelancerGuruAndPeoplePerHourAlertsAreParsed() throws Exception {
        List<Job> freelancer = parse("freelancer-alert");
        assertThat(freelancer).hasSize(2);
        assertThat(freelancer.get(0).url()).isEqualTo("https://www.freelancer.com/projects/java/Spring-Boot-payment-microservice");
        assertThat(freelancer.get(0).budget()).isEqualTo(Budget.of(500, 1500, "USD", false));

        List<Job> guru = parse("guru-alert");
        assertThat(guru).hasSize(1);
        assertThat(guru.get(0).url()).isEqualTo("https://www.guru.com/jobs/java-backend-developer-needed/2012345");
        assertThat(guru.get(0).budget()).isEqualTo(Budget.of(2000, null, "USD", false));

        List<Job> pph = parse("peopleperhour-alert");
        assertThat(pph).hasSize(1);
        assertThat(pph.get(0).source()).isEqualTo("email:peopleperhour");
        assertThat(pph.get(0).budget()).isEqualTo(Budget.of(400, null, "GBP", false));
        assertThat(pph.get(0).url()).isEqualTo("https://www.peopleperhour.com/freelance-jobs/technology-programming/java-developer-for-api-integration-4412345");
    }

    @Test
    void kworkAlertIsRussianMarketWithRoubleBudgets() throws Exception {
        List<Job> jobs = parse("kwork-alert");

        assertThat(jobs).hasSize(2);
        Job first = jobs.get(0);
        assertThat(first.source()).isEqualTo("email:kwork");
        assertThat(first.url()).isEqualTo("https://kwork.ru/projects/2345678");
        assertThat(first.title()).isEqualTo("Доработать Java-сервис платежей");
        assertThat(first.market()).isEqualTo(Market.RU);
        assertThat(first.budget()).isEqualTo(Budget.of(15000, null, "RUB", false));
        assertThat(jobs.get(1).budget()).isEqualTo(Budget.of(2000, null, "RUB", false));
    }

    @Test
    void senderMatchingUsesTheDomainExactlyOrAsAParentDomain() {
        AlertParser upwork = AlertParsers.all().get(0);

        assertThat(upwork.supports(new EmailMessageData("a@upwork.com", "", "", "", null, null))).isTrue();
        assertThat(upwork.supports(new EmailMessageData("a@mail.upwork.com", "", "", "", null, null))).isTrue();
        assertThat(upwork.supports(new EmailMessageData("a@notupwork.com", "", "", "", null, null))).isFalse();
        assertThat(upwork.supports(new EmailMessageData("a@upwork.com.evil.test", "", "", "", null, null))).isFalse();
    }

    @Test
    void anEmailWithoutHtmlOrWithoutJobLinksYieldsNothing() {
        AlertParser upwork = AlertParsers.all().get(0);

        assertThat(upwork.parse(new EmailMessageData("a@upwork.com", "s", null, "plain text only", null, null))).isEmpty();
        assertThat(upwork.parse(new EmailMessageData("a@upwork.com", "s", "<p><a href=\"https://www.upwork.com/help\">Help</a></p>", "", null, null))).isEmpty();
    }
}
