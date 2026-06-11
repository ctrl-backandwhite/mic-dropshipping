package com.nexaplatform.dropshipping.infrastructure.integration.translation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Echoes the source text — used in dev / when translation is disabled. */
@Component
@ConditionalOnProperty(prefix = "nexadrop.translation", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoopTranslationProvider implements TranslationProvider {

    @Override
    public String name() {
        return "noop";
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String translate(String text, String sourceLang, String targetLang) {
        return text;
    }
}
