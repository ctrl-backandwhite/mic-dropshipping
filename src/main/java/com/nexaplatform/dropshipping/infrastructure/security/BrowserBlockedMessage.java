package com.nexaplatform.dropshipping.infrastructure.security;

/**
 * Localized "this API is server-to-server only" message returned to browser callers of {@code /api/v1/**}.
 * Type-safe alternative to a string map: each constant pairs a language code with its message and
 * {@link #forCode(String)} resolves the consumer's language, defaulting to {@link #EN English}.
 */
public enum BrowserBlockedMessage {

    EN("en", "This API must be consumed server-to-server. Build a backend and call it from there; "
            + "direct browser/frontend calls are not allowed."), ES(
                    "es",
                    "Esta API debe consumirse de servidor a servidor. Crea un backend y llámala desde ahí; "
                            + "no se permiten llamadas directas desde el navegador/frontend."), PT(
                                    "pt",
                                    "Esta API deve ser consumida de servidor para servidor. Crie um backend e chame-a a partir "
                                            + "dele; chamadas diretas do navegador/frontend não são permitidas."), FR(
                                                    "fr",
                                                    "Cette API doit être consommée de serveur à serveur. Créez un backend et appelez-la depuis "
                                                            + "celui-ci ; les appels directs depuis le navigateur/frontend ne sont pas autorisés."), DE(
                                                                    "de",
                                                                    "Diese API muss server-zu-server genutzt werden. Erstellen Sie ein Backend und rufen Sie sie "
                                                                            + "von dort auf; direkte Browser-/Frontend-Aufrufe sind nicht erlaubt."), IT(
                                                                                    "it",
                                                                                    "Questa API deve essere consumata server-to-server. Crea un backend e chiamala da lì; "
                                                                                            + "le chiamate dirette dal browser/frontend non sono consentite."), NL(
                                                                                                    "nl",
                                                                                                    "Deze API moet server-naar-server worden gebruikt. Bouw een backend en roep deze daarvandaan "
                                                                                                            + "aan; directe browser-/frontend-aanroepen zijn niet toegestaan."), ZH(
                                                                                                                    "zh",
                                                                                                                    "此 API 必须以服务器到服务器的方式调用。请搭建后端并从后端调用；不允许直接从浏览器/前端调用。");

    private final String code;
    private final String message;

    BrowserBlockedMessage(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String message() {
        return message;
    }

    /** Resolves the message for a 2-letter language code; falls back to {@link #EN} when unknown/blank. */
    public static BrowserBlockedMessage forCode(String langCode) {
        if (langCode != null) {
            String normalized = langCode.trim().toLowerCase();
            for (BrowserBlockedMessage m : values()) {
                if (m.code.equals(normalized)) {
                    return m;
                }
            }
        }
        return EN;
    }
}
