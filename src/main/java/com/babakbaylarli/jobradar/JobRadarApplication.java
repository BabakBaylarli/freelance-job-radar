package com.babakbaylarli.jobradar;

import com.babakbaylarli.jobradar.config.JobRadarProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(JobRadarProperties.class)
public class JobRadarApplication {

    public static void main(String[] args) {
        if (java.util.Arrays.asList(args).contains("--telegram-login")) {
            // one-time interactive login for the Telegram channel source; does not start the application
            System.exit(com.babakbaylarli.jobradar.source.telegram.TelegramLoginCli.run(System.getenv()));
        }
        SpringApplication.run(JobRadarApplication.class, args);
    }
}
