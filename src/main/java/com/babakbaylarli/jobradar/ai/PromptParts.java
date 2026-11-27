package com.babakbaylarli.jobradar.ai;

/** A system prompt (rules, profile) and a user prompt (the job) ready to send to the model. */
public record PromptParts(String system, String user) {}
