package com.nexaplatform.dropshipping.domain.enums;

/**
 * Textos del email de confirmación al contratar un plan, en los 8 idiomas soportados. Se envía en el
 * idioma del usuario. Los cuerpos llevan los marcadores {@code {name}}, {@code {plan}} y {@code {date}};
 * la sustitución la hace el servicio que envía. Mismo patrón que {@link AuthEmailLabel}.
 */
public enum SubscriptionEmailLabel {

    SUBJECT(new Translations("Tu plan en NX036", "Your NX036 plan", "O teu plano na NX036", "你的 NX036 套餐",
            "Votre forfait NX036", "Dein NX036-Tarif", "Il tuo piano NX036", "Je NX036-abonnement")),
    TITLE(new Translations("Plan contratado", "Plan activated", "Plano contratado", "套餐已开通",
            "Forfait activé", "Tarif aktiviert", "Piano attivato", "Abonnement geactiveerd")),
    BODY_TRIAL(new Translations(
            "Hola {name}, has activado tu prueba gratis de 15 días del plan {plan}. Termina el {date}; después elige uno de los planes de pago para seguir usando la plataforma.",
            "Hi {name}, you've started your 15-day free trial of the {plan} plan. It ends on {date}; after that, choose one of the paid plans to keep using the platform.",
            "Olá {name}, ativaste a tua avaliação gratuita de 15 dias do plano {plan}. Termina a {date}; depois escolhe um dos planos pagos para continuar a usar a plataforma.",
            "您好 {name},您已开通 {plan} 套餐的 15 天免费试用。将于 {date} 结束;之后请选择一个付费套餐以继续使用平台。",
            "Bonjour {name}, vous avez activé votre essai gratuit de 15 jours du forfait {plan}. Il se termine le {date} ; ensuite, choisissez un forfait payant pour continuer à utiliser la plateforme.",
            "Hallo {name}, du hast deine 15-tägige kostenlose Testphase des Tarifs {plan} gestartet. Sie endet am {date}; danach wähle einen kostenpflichtigen Tarif, um die Plattform weiter zu nutzen.",
            "Ciao {name}, hai attivato la tua prova gratuita di 15 giorni del piano {plan}. Termina il {date}; poi scegli uno dei piani a pagamento per continuare a usare la piattaforma.",
            "Hallo {name}, je bent je gratis proefperiode van 15 dagen van het {plan}-abonnement gestart. Deze eindigt op {date}; kies daarna een betaald abonnement om het platform te blijven gebruiken.")),
    BODY_PAID(new Translations(
            "Hola {name}, has contratado el plan {plan}. ¡Gracias por confiar en NX036! Puedes ver o cambiar tu plan cuando quieras desde tu perfil.",
            "Hi {name}, you've subscribed to the {plan} plan. Thanks for choosing NX036! You can view or change your plan anytime from your profile.",
            "Olá {name}, contrataste o plano {plan}. Obrigado por escolheres a NX036! Podes ver ou alterar o teu plano quando quiseres no teu perfil.",
            "您好 {name},您已订购 {plan} 套餐。感谢选择 NX036!您可以随时在个人资料中查看或更改套餐。",
            "Bonjour {name}, vous avez souscrit au forfait {plan}. Merci d'avoir choisi NX036 ! Vous pouvez consulter ou modifier votre forfait à tout moment depuis votre profil.",
            "Hallo {name}, du hast den Tarif {plan} abonniert. Danke, dass du dich für NX036 entschieden hast! Du kannst deinen Tarif jederzeit in deinem Profil ansehen oder ändern.",
            "Ciao {name}, hai sottoscritto il piano {plan}. Grazie per aver scelto NX036! Puoi vedere o cambiare il tuo piano quando vuoi dal tuo profilo.",
            "Hallo {name}, je hebt het {plan}-abonnement afgesloten. Bedankt dat je voor NX036 kiest! Je kunt je abonnement altijd bekijken of wijzigen in je profiel.")),
    CTA(new Translations("Ver mi plan", "View my plan", "Ver o meu plano", "查看我的套餐",
            "Voir mon forfait", "Meinen Tarif ansehen", "Vedi il mio piano", "Mijn abonnement bekijken"));

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

    private final Translations translations;

    SubscriptionEmailLabel(Translations translations) {
        this.translations = translations;
    }

    public String of(String lang) {
        return translations.of(lang);
    }
}
