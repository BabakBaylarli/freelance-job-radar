package com.babakbaylarli.jobradar.source.email;

import com.babakbaylarli.jobradar.source.email.LinkListAlertParser.Config;
import com.babakbaylarli.jobradar.source.JobType;
import com.babakbaylarli.jobradar.source.Market;
import java.util.List;
import java.util.regex.Pattern;

/** The known senders of job alert e-mails. Each is only a configuration of {@link LinkListAlertParser}. */
public final class AlertParsers {

    private AlertParsers() {}

    public static List<AlertParser> all() {
        return List.of(
                parser("upwork", List.of("upwork.com"), "upwork\\.com/(?:[\\w\\-/]*?)(~0[0-9a-f]{10,})", "https://www.upwork.com/jobs/%s",
                        Market.INTERNATIONAL, JobType.FREELANCE_PROJECT, "USD", false),
                parser("djinni", List.of("djinni.co"), "djinni\\.co/jobs/(\\d+)", "https://djinni.co/jobs/%s/",
                        Market.UA, JobType.VACANCY, "USD", true),
                parser("linkedin", List.of("linkedin.com"), "linkedin\\.com/(?:comm/)?jobs/view/(?:[\\w\\-]*-)?(\\d{6,})", "https://www.linkedin.com/jobs/view/%s",
                        Market.INTERNATIONAL, JobType.VACANCY, "USD", true),
                parser("freelancer", List.of("freelancer.com"), "freelancer\\.com/projects/([\\w\\-]+/[\\w\\-]+)", "https://www.freelancer.com/projects/%s",
                        Market.INTERNATIONAL, JobType.FREELANCE_PROJECT, "USD", false),
                parser("guru", List.of("guru.com"), "guru\\.com/jobs/([\\w\\-%]+/\\d+)", "https://www.guru.com/jobs/%s",
                        Market.INTERNATIONAL, JobType.FREELANCE_PROJECT, "USD", false),
                parser("peopleperhour", List.of("peopleperhour.com"), "peopleperhour\\.com/freelance-jobs/([\\w\\-/]+-\\d+)", "https://www.peopleperhour.com/freelance-jobs/%s",
                        Market.INTERNATIONAL, JobType.FREELANCE_PROJECT, "GBP", false),
                parser("kwork", List.of("kwork.ru", "kwork.com"), "kwork\\.(?:ru|com)/projects/(\\d+)", "https://kwork.ru/projects/%s",
                        Market.RU, JobType.FREELANCE_PROJECT, "RUB", false));
    }

    private static AlertParser parser(String platform, List<String> domains, String linkRegex, String template, Market market, JobType type,
            String currency, boolean companyAfterTitle) {
        return new LinkListAlertParser(new Config(platform, domains, Pattern.compile(linkRegex, Pattern.CASE_INSENSITIVE), template, market, type, currency, companyAfterTitle));
    }
}
