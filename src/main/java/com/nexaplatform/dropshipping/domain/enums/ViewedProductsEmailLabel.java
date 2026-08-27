package com.nexaplatform.dropshipping.domain.enums;

/**
 * Textos del correo recordatorio «lo que has estado mirando» en los ocho idiomas soportados. Mismo patrón
 * que {@link NewProductsEmailLabel}. Se envía en el idioma del usuario.
 */
public enum ViewedProductsEmailLabel {

    SUBJECT(new Translations("Lo que has estado mirando en NX036", "What you've been looking at on NX036",
            "O que tem andado a ver na NX036", "您最近在 NX036 浏览过的商品",
            "Ce que vous avez consulté sur NX036", "Was du dir bei NX036 angesehen hast",
            "Quello che hai guardato su NX036", "Wat je hebt bekeken op NX036")),
    TITLE(new Translations("Sigue donde lo dejaste", "Pick up where you left off",
            "Continue de onde ficou", "继续您上次浏览的商品", "Reprenez où vous en étiez",
            "Mach da weiter, wo du aufgehört hast", "Riprendi da dove eri rimasto",
            "Ga verder waar je was gebleven")),
    INTRO(new Translations(
            "Estas son las fichas que has visitado en los últimos tres días, por si te quedó alguna pendiente.",
            "These are the products you visited over the last three days, in case you left one pending.",
            "Estes são os produtos que visitou nos últimos três dias, caso tenha ficado algum pendente.",
            "以下是您最近三天浏览过的商品,以免遗漏。",
            "Voici les fiches que vous avez consultées ces trois derniers jours, au cas où il en resterait une en attente.",
            "Das sind die Produkte, die du dir in den letzten drei Tagen angesehen hast — falls eines offengeblieben ist.",
            "Questi sono i prodotti che hai visitato negli ultimi tre giorni, nel caso ne fosse rimasto qualcuno in sospeso.",
            "Dit zijn de producten die je de afgelopen drie dagen hebt bekeken, voor het geval er nog een openstond.")),
    CTA(new Translations("Ver mi historial completo", "See my full history", "Ver o meu histórico completo",
            "查看我的完整浏览记录", "Voir tout mon historique", "Meinen gesamten Verlauf ansehen",
            "Vedi la mia cronologia completa", "Bekijk mijn volledige geschiedenis")),
    FOOTER(new Translations(
            "Recibes este correo porque has abierto fichas de producto con tu cuenta de NX036. Ese historial se guarda 90 días y se explica en los términos y condiciones.",
            "You're receiving this because you opened product pages while signed in to NX036. That history is kept for 90 days and is explained in the terms and conditions.",
            "Recebe este e-mail porque abriu páginas de produto com a sua conta NX036. Esse histórico é guardado 90 dias e está explicado nos termos e condições.",
            "您收到此邮件是因为您在登录 NX036 后浏览过商品页面。该浏览记录保留 90 天,详见条款与条件。",
            "Vous recevez cet e-mail car vous avez consulté des fiches produit en étant connecté à NX036. Cet historique est conservé 90 jours et expliqué dans les conditions générales.",
            "Du erhältst diese E-Mail, weil du angemeldet Produktseiten bei NX036 geöffnet hast. Dieser Verlauf wird 90 Tage gespeichert und ist in den Bedingungen erklärt.",
            "Ricevi questa email perché hai aperto schede prodotto con il tuo account NX036. Questa cronologia si conserva 90 giorni ed è spiegata nei termini e condizioni.",
            "Je ontvangt deze e-mail omdat je ingelogd productpagina's op NX036 hebt geopend. Die geschiedenis wordt 90 dagen bewaard en staat uitgelegd in de voorwaarden.")),
    UNSUBSCRIBE(new Translations("Dejar de recibir este recordatorio", "Stop receiving this reminder",
            "Deixar de receber este lembrete", "取消接收此提醒", "Ne plus recevoir ce rappel",
            "Diese Erinnerung nicht mehr erhalten", "Non ricevere più questo promemoria",
            "Deze herinnering niet meer ontvangen"));

    /**
     * Las ocho traducciones de una etiqueta. Van agrupadas porque como parámetros sueltos del constructor
     * eran ocho String seguidos: colar el texto alemán en el hueco del italiano no da error de
     * compilación y el correo sale en otro idioma sin que nadie se entere.
     */
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

    ViewedProductsEmailLabel(Translations translations) {
        this.translations = translations;
    }

    public String of(String lang) {
        return translations.of(lang);
    }
}
