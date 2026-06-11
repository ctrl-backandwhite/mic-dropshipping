package com.nexaplatform.dropshipping.infrastructure.integration.translation;

import com.google.cloud.translate.Translate;
import com.google.cloud.translate.TranslateOptions;
import com.google.cloud.translate.Translation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "nexadrop.translation", name = "enabled", havingValue = "true")
public class GoogleTranslationProvider implements TranslationProvider {

    private final Translate translate = TranslateOptions.getDefaultInstance().getService();

    @Override
    public String name() {
        return "google";
    }

    @Override
    public boolean available() {
        return translate != null;
    }

    @Override
    public String translate(String text, String sourceLang, String targetLang) {
        if (text == null || text.isBlank()) return text;
        Translation t = translate.translate(text,
                Translate.TranslateOption.sourceLanguage(sourceLang),
                Translate.TranslateOption.targetLanguage(targetLang),
                Translate.TranslateOption.format("text"));
        return t.getTranslatedText();
    }
}
