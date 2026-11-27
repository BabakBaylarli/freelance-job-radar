package com.babakbaylarli.jobradar.ai;

/** One job the model extracted from an e-mail it did not have a dedicated parser for (already validated). */
public record ExtractedJob(String title, String url, String company, String budget, String description) {}
