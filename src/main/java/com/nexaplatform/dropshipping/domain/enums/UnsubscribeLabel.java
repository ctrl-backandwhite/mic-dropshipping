package com.nexaplatform.dropshipping.domain.enums;

/**
 * Textos de la página de confirmación de baja/alta de los correos de novedades, en los 8 idiomas soportados.
 * Mismo patrón que {@link NewProductsEmailLabel}.
 */
public enum UnsubscribeLabel {

    OFF_TITLE("Te has dado de baja", "You're unsubscribed", "Cancelou a subscrição", "已取消订阅",
            "Vous êtes désabonné", "Du bist abgemeldet", "Iscrizione annullata", "Je bent uitgeschreven"),
    OFF_MESSAGE("Ya no recibirás correos con las novedades del catálogo. Si ha sido un error, puedes volver a activarlos.",
            "You'll no longer receive our new-arrival emails. If this was a mistake, you can turn them back on.",
            "Deixará de receber os e-mails de novidades do catálogo. Se foi um engano, pode reativá-los.",
            "您将不再收到我们的上新邮件。如果这是误操作,您可以重新开启。",
            "Vous ne recevrez plus nos e-mails de nouveautés. En cas d'erreur, vous pouvez les réactiver.",
            "Du erhältst keine Neuheiten-E-Mails mehr. War das ein Versehen, kannst du sie wieder aktivieren.",
            "Non riceverai più le nostre email sulle novità. Se è stato un errore, puoi riattivarle.",
            "Je ontvangt geen nieuwe-producten-mails meer. Was dit een vergissing? Je kunt ze weer inschakelen."),
    RESUBSCRIBE_LABEL("Volver a recibir novedades", "Receive new arrivals again", "Voltar a receber novidades",
            "重新接收上新", "Recevoir à nouveau les nouveautés", "Neuheiten wieder erhalten",
            "Ricevi di nuovo le novità", "Weer nieuwe producten ontvangen"),
    ON_TITLE("Suscripción reactivada", "Subscription reactivated", "Subscrição reativada", "订阅已恢复",
            "Abonnement réactivé", "Abo reaktiviert", "Iscrizione riattivata", "Abonnement heractiveerd"),
    ON_MESSAGE("Volverás a recibir nuestros correos con las novedades del catálogo.",
            "You'll receive our new-arrival emails again.",
            "Voltará a receber os nossos e-mails de novidades do catálogo.",
            "您将重新收到我们的上新邮件。",
            "Vous recevrez à nouveau nos e-mails de nouveautés.",
            "Du erhältst wieder unsere Neuheiten-E-Mails.",
            "Riceverai di nuovo le nostre email sulle novità.",
            "Je ontvangt onze nieuwe-producten-mails weer."),
    INVALID_TITLE("Enlace no válido", "Invalid link", "Ligação inválida", "链接无效",
            "Lien non valide", "Ungültiger Link", "Link non valido", "Ongeldige link"),
    INVALID_MESSAGE("Este enlace de baja no es válido o ha caducado.",
            "This unsubscribe link is invalid or has expired.",
            "Esta ligação de cancelamento é inválida ou expirou.",
            "此退订链接无效或已过期。",
            "Ce lien de désabonnement n'est pas valide ou a expiré.",
            "Dieser Abmeldelink ist ungültig oder abgelaufen.",
            "Questo link di annullamento non è valido o è scaduto.",
            "Deze uitschrijflink is ongeldig of verlopen."),
    HOME_LABEL("Ir a NX036", "Go to NX036", "Ir para a NX036", "前往 NX036",
            "Aller sur NX036", "Zu NX036", "Vai su NX036", "Naar NX036");

    private final String es;
    private final String en;
    private final String pt;
    private final String zh;
    private final String fr;
    private final String de;
    private final String it;
    private final String nl;

    UnsubscribeLabel(String es, String en, String pt, String zh, String fr, String de, String it, String nl) {
        this.es = es; this.en = en; this.pt = pt; this.zh = zh; this.fr = fr; this.de = de; this.it = it; this.nl = nl;
    }

    public String of(String lang) {
        return switch (lang == null ? "es" : lang) {
            case "en" -> en; case "pt" -> pt; case "zh" -> zh; case "fr" -> fr;
            case "de" -> de; case "it" -> it; case "nl" -> nl; default -> es;
        };
    }
}
