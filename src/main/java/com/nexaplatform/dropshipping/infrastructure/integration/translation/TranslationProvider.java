package com.nexaplatform.dropshipping.infrastructure.integration.translation;

public interface TranslationProvider {
    String name();

    boolean available();

    String translate(String text, String sourceLang, String targetLang);
}
