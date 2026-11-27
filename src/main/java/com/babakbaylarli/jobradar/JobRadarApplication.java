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
        SpringApplication.run(JobRadarApplication.class, args);
    }
}
