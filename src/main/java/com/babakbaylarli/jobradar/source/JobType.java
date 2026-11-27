package com.babakbaylarli.jobradar.source;

public enum JobType {
    FREELANCE_PROJECT("Freelance project"),
    REMOTE_JOB("Remote job"),
    VACANCY("Vacancy");

    private final String label;

    JobType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
