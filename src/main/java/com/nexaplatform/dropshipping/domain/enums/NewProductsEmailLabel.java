package com.nexaplatform.dropshipping.domain.enums;

/**
 * Textos de la campaña diaria "Nuevos productos" en los 8 idiomas soportados. Mismo patrón que
 * {@link AuthEmailLabel}. Se envía en el idioma del usuario.
 */
public enum NewProductsEmailLabel {

    SUBJECT(new Translations("Nuevos productos en NX036 · Novedades de hoy",
            "New products at NX036 · Today's arrivals", "Novos produtos na NX036 · Novidades de hoje",
            "NX036 上新 · 今日新品", "Nouveautés chez NX036 · Les arrivages du jour",
            "Neue Produkte bei NX036 · Neuheiten von heute", "Nuovi prodotti su NX036 · Le novità di oggi",
            "Nieuwe producten bij NX036 · Vandaag toegevoegd")),
    TITLE(new Translations("Nuevos productos disponibles hoy", "New products available today",
            "Novos produtos disponíveis hoje", "今日上新", "Nouveaux produits disponibles aujourd'hui",
            "Neue Produkte ab heute verfügbar", "Nuovi prodotti disponibili oggi",
            "Nieuwe producten vanaf vandaag beschikbaar")),
    INTRO(new Translations(
            "Hemos añadido novedades al catálogo. Aquí tienes una muestra por categoría; entra para verlas todas.",
            "We've added new items to the catalog. Here's a sample by category; sign in to see them all.",
            "Adicionámos novidades ao catálogo. Aqui tem uma amostra por categoria; entre para ver todas.",
            "我们上架了新品。以下是各分类的精选,登录即可查看全部。",
            "Nous avons ajouté des nouveautés au catalogue. Voici un aperçu par catégorie ; connectez-vous pour tout voir.",
            "Wir haben Neuheiten in den Katalog aufgenommen. Hier eine Auswahl nach Kategorie; melde dich an, um alles zu sehen.",
            "Abbiamo aggiunto novità al catalogo. Ecco un assaggio per categoria; accedi per vederle tutte.",
            "We hebben nieuwe items aan de catalogus toegevoegd. Hier een selectie per categorie; log in om alles te zien.")),
    CTA(new Translations("Ver todas las novedades", "See all new arrivals", "Ver todas as novidades", "查看全部新品",
            "Voir toutes les nouveautés", "Alle Neuheiten ansehen", "Vedi tutte le novità",
            "Bekijk alle nieuwe items")),
    FOOTER(new Translations(
            "Recibes este correo porque estás registrado en NX036. Para ver el listado completo se te pedirá iniciar sesión.",
            "You're receiving this because you're registered at NX036. You'll be asked to sign in to see the full list.",
            "Recebe este e-mail porque está registado na NX036. Ser-lhe-á pedido para iniciar sessão para ver a lista completa.",
            "您收到此邮件是因为您已注册 NX036。查看完整列表需要登录。",
            "Vous recevez cet e-mail car vous êtes inscrit sur NX036. La connexion sera requise pour voir la liste complète.",
            "Du erhältst diese E-Mail, weil du bei NX036 registriert bist. Für die vollständige Liste ist eine Anmeldung nötig.",
            "Ricevi questa email perché sei registrato su NX036. Per vedere l'elenco completo ti verrà chiesto di accedere.",
            "Je ontvangt deze e-mail omdat je bij NX036 bent geregistreerd. Je wordt gevraagd in te loggen om de volledige lijst te zien.")),
    UNSUBSCRIBE(new Translations("Darse de baja de los correos de novedades", "Unsubscribe from new-arrival emails",
            "Cancelar a subscrição dos e-mails de novidades", "取消订阅上新邮件",
            "Se désabonner des e-mails de nouveautés", "Neuheiten-E-Mails abbestellen",
            "Annulla l'iscrizione alle email sulle novità", "Uitschrijven voor nieuwe-producten-mails"));

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

    NewProductsEmailLabel(Translations translations) {
        this.translations = translations;
    }

    public String of(String lang) {
        return translations.of(lang);
    }
}
