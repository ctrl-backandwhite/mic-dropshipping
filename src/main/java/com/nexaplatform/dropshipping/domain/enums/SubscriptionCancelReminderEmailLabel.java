package com.nexaplatform.dropshipping.domain.enums;

/**
 * Textos del email de RECORDATORIO de cancelación de plan (los 3 días previos, uno al día), en los 8
 * idiomas. El cuerpo lleva los marcadores {@code {plan}} y {@code {date}}. Mismo patrón que
 * {@link SubscriptionEmailLabel}.
 */
public enum SubscriptionCancelReminderEmailLabel {

    SUBJECT(new Translations("Tu plan se cancela pronto · NX036", "Your plan is ending soon · NX036",
            "O teu plano termina em breve · NX036", "你的套餐即将取消 · NX036", "Votre forfait se termine bientôt · NX036",
            "Dein Tarif endet bald · NX036", "Il tuo piano sta per terminare · NX036",
            "Je abonnement eindigt binnenkort · NX036")), TITLE(
                    new Translations("Tu plan se cancela pronto", "Your plan is ending soon",
                            "O teu plano termina em breve", "你的套餐即将取消", "Votre forfait se termine bientôt",
                            "Dein Tarif endet bald", "Il tuo piano sta per terminare",
                            "Je abonnement eindigt binnenkort")), BODY(
                                    new Translations(
                                            "Te recordamos que tu plan {plan} se cancelará el {date}. Hasta esa fecha conservas todas sus "
                                                    + "funciones. Si quieres seguir usándolo, contrata un plan antes de que venza.",
                                            "This is a reminder that your {plan} plan will be canceled on {date}. You keep all its features "
                                                    + "until then. If you'd like to keep using it, subscribe again before it ends.",
                                            "Lembramos que o teu plano {plan} será cancelado a {date}. Até lá manténs todas as funções. Se "
                                                    + "quiseres continuar a usá-lo, contrata um plano antes de terminar.",
                                            "提醒您，您的 {plan} 套餐将于 {date} 取消。在此之前您仍可使用全部功能。如需继续使用，请在到期前重新订阅。",
                                            "Nous vous rappelons que votre forfait {plan} sera annulé le {date}. Vous conservez toutes ses "
                                                    + "fonctionnalités jusque-là. Pour continuer à en profiter, souscrivez à nouveau avant la fin.",
                                            "Wir erinnern dich daran, dass dein Tarif {plan} am {date} gekündigt wird. Bis dahin behältst du "
                                                    + "alle Funktionen. Wenn du ihn weiter nutzen möchtest, schließe vorher erneut einen Tarif ab.",
                                            "Ti ricordiamo che il tuo piano {plan} sarà annullato il {date}. Fino ad allora mantieni tutte le "
                                                    + "funzioni. Se vuoi continuare a usarlo, sottoscrivi di nuovo un piano prima della scadenza.",
                                            "Ter herinnering: je {plan}-abonnement wordt op {date} opgezegd. Tot dan behoud je alle functies. "
                                                    + "Wil je het blijven gebruiken, sluit dan opnieuw een abonnement af voordat het eindigt.")), CTA(
                                                            new Translations("Ver mi plan", "View my plan",
                                                                    "Ver o meu plano", "查看我的套餐", "Voir mon forfait",
                                                                    "Meinen Tarif ansehen", "Vedi il mio piano",
                                                                    "Mijn abonnement bekijken"));

    private final Translations translations;

    SubscriptionCancelReminderEmailLabel(Translations translations) {
        this.translations = translations;
    }

    public String of(String lang) {
        return translations.of(lang);
    }

    private record Translations(String es, String en, String pt, String zh, String fr, String de, String it,
            String nl) {

        String of(String lang) {
            return switch (lang == null ? "es" : lang) {
                case "en" -> en;
                case "pt" -> pt;
                case "zh" -> zh;
                case "fr" -> fr;
                case "de" -> de;
                case "it" -> it;
                case "nl" -> nl;
                default -> es;
            };
        }
    }
}
