package com.babakbaylarli.jobradar.bot;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Conditions on whether {@code TELEGRAM_BOT_TOKEN} is set (a blank value counts as not set). */
public final class TelegramConditions {

    private static final String TOKEN_PROPERTY = "jobradar.telegram.bot-token";

    private TelegramConditions() {}

    static boolean tokenPresent(ConditionContext context) {
        String token = context.getEnvironment().getProperty(TOKEN_PROPERTY);
        return token != null && !token.isBlank();
    }

    public static class BotConfigured implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return tokenPresent(context);
        }
    }

    public static class BotMissing implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !tokenPresent(context);
        }
    }
}
