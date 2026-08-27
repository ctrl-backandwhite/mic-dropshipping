package com.nexaplatform.dropshipping.domain.enums;

/**
 * Textos del aviso de actualización de los términos y la política de privacidad, en los 8 idiomas.
 *
 * <p><b>Este correo NO es publicidad.</b> Comunica un cambio en las condiciones del servicio que el usuario
 * ha aceptado, así que se envía a todo el mundo — también a quien tiene desactivadas las comunicaciones
 * comerciales. Confundir las dos cosas tiene consecuencias en las dos direcciones: mandar publicidad a quien
 * la rechazó es una infracción, y callarse un cambio de condiciones deja al usuario vinculado por un texto
 * que nunca vio. Por eso el pie NO lleva enlace de baja: no hay nada de lo que darse de baja.
 *
 * <p>Mismo patrón que {@link NewProductsEmailLabel}: un enum con sus traducciones, no un mapa.
 */
public enum LegalUpdateEmailLabel {

    SUBJECT(new Translations("Hemos actualizado nuestros términos y la política de privacidad",
            "We've updated our terms and privacy policy",
            "Atualizámos os nossos termos e a política de privacidade",
            "我们更新了服务条款和隐私政策",
            "Nous avons mis à jour nos conditions et notre politique de confidentialité",
            "Wir haben unsere AGB und Datenschutzerklärung aktualisiert",
            "Abbiamo aggiornato i nostri termini e l'informativa sulla privacy",
            "We hebben onze voorwaarden en privacybeleid bijgewerkt")),

    TITLE(new Translations("Cambios en nuestras condiciones", "Changes to our terms",
            "Alterações às nossas condições", "条款变更", "Modification de nos conditions",
            "Änderungen an unseren Bedingungen", "Modifiche alle nostre condizioni",
            "Wijzigingen in onze voorwaarden")),

    INTRO(new Translations(
            "Hemos publicado una nueva versión de nuestros términos de uso y de la política de privacidad. "
                    + "Te avisamos porque tienes una cuenta con nosotros y estos textos te afectan.",
            "We've published a new version of our terms of use and privacy policy. We're letting you know "
                    + "because you have an account with us and these documents apply to you.",
            "Publicámos uma nova versão dos nossos termos de utilização e da política de privacidade. "
                    + "Avisamos porque tem uma conta connosco e estes textos aplicam-se a si.",
            "我们发布了新版服务条款和隐私政策。因为您在我们这里有账户,这些条款与您有关,特此通知。",
            "Nous avons publié une nouvelle version de nos conditions d'utilisation et de notre politique de "
                    + "confidentialité. Nous vous prévenons car vous avez un compte et ces textes vous concernent.",
            "Wir haben eine neue Fassung unserer Nutzungsbedingungen und Datenschutzerklärung veröffentlicht. "
                    + "Wir informieren dich, weil du ein Konto hast und diese Texte für dich gelten.",
            "Abbiamo pubblicato una nuova versione dei nostri termini d'uso e dell'informativa sulla privacy. "
                    + "Ti avvisiamo perché hai un account e questi testi ti riguardano.",
            "We hebben een nieuwe versie van onze gebruiksvoorwaarden en ons privacybeleid gepubliceerd. "
                    + "We laten het je weten omdat je een account hebt en deze teksten op jou van toepassing zijn.")),

    /** Se le dice qué puede hacer si no está de acuerdo: sin salida, el aviso es un trámite vacío. */
    WHAT_TO_DO(new Translations(
            "Te recomendamos leerlos. Si no estás de acuerdo con los nuevos términos, puedes cerrar tu cuenta "
                    + "en cualquier momento desde tu perfil, sin dar explicaciones.",
            "We recommend reading them. If you don't agree with the new terms, you can close your account at "
                    + "any time from your profile, no explanation needed.",
            "Recomendamos que os leia. Se não concordar com os novos termos, pode encerrar a sua conta a "
                    + "qualquer momento a partir do seu perfil, sem dar explicações.",
            "建议您阅读。如果您不同意新条款,可以随时在个人资料页关闭账户,无需说明理由。",
            "Nous vous invitons à les lire. Si vous n'acceptez pas les nouvelles conditions, vous pouvez "
                    + "fermer votre compte à tout moment depuis votre profil, sans avoir à vous justifier.",
            "Wir empfehlen dir, sie zu lesen. Wenn du mit den neuen Bedingungen nicht einverstanden bist, "
                    + "kannst du dein Konto jederzeit im Profil schließen, ohne Angabe von Gründen.",
            "Ti consigliamo di leggerli. Se non sei d'accordo con i nuovi termini, puoi chiudere il tuo "
                    + "account in qualsiasi momento dal tuo profilo, senza dare spiegazioni.",
            "We raden je aan ze te lezen. Als je het niet eens bent met de nieuwe voorwaarden, kun je je "
                    + "account op elk moment sluiten via je profiel, zonder uitleg.")),

    CTA_PRIVACY(new Translations("Leer la política de privacidad", "Read the privacy policy",
            "Ler a política de privacidade", "阅读隐私政策", "Lire la politique de confidentialité",
            "Datenschutzerklärung lesen", "Leggi l'informativa sulla privacy", "Lees het privacybeleid")),

    CTA_TERMS(new Translations("Leer los términos de uso", "Read the terms of use",
            "Ler os termos de utilização", "阅读服务条款", "Lire les conditions d'utilisation",
            "Nutzungsbedingungen lesen", "Leggi i termini d'uso", "Lees de gebruiksvoorwaarden")),

    /** Sin enlace de baja, y diciendo por qué: es un aviso de servicio, no una lista de correo. */
    FOOTER(new Translations(
            "Recibes este aviso porque tienes una cuenta en NX036. No es publicidad: te informamos de un "
                    + "cambio en las condiciones del servicio, así que no se puede desactivar.",
            "You're receiving this because you have an NX036 account. This isn't marketing: it's a notice "
                    + "about a change to the terms of the service, so it can't be turned off.",
            "Recebe este aviso porque tem uma conta na NX036. Não é publicidade: informamos de uma alteração "
                    + "nas condições do serviço, pelo que não pode ser desativado.",
            "您收到此通知是因为您拥有 NX036 账户。这不是广告,而是服务条款变更通知,因此无法关闭。",
            "Vous recevez cet avis car vous avez un compte NX036. Ce n'est pas de la publicité : il s'agit "
                    + "d'un changement des conditions du service, il ne peut donc pas être désactivé.",
            "Du erhältst diesen Hinweis, weil du ein NX036-Konto hast. Das ist keine Werbung, sondern eine "
                    + "Mitteilung über geänderte Servicebedingungen und lässt sich daher nicht abbestellen.",
            "Ricevi questo avviso perché hai un account NX036. Non è pubblicità: ti informiamo di una "
                    + "modifica delle condizioni del servizio, quindi non può essere disattivato.",
            "Je ontvangt deze melding omdat je een NX036-account hebt. Dit is geen reclame: het is een "
                    + "kennisgeving over gewijzigde servicevoorwaarden en kan daarom niet worden uitgezet."));

    /** Mismo record privado que el resto de enums de correo: las traducciones viajan con su etiqueta. */
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

    LegalUpdateEmailLabel(Translations translations) {
        this.translations = translations;
    }

    /** El texto en el idioma dado; español si el idioma no está soportado. */
    public String of(String lang) {
        return translations.of(lang);
    }
}
