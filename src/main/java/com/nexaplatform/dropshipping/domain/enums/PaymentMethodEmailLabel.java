package com.nexaplatform.dropshipping.domain.enums;

/**
 * Textos del correo con el CÓDIGO para confirmar la eliminación de un método de pago, en los 8 idiomas.
 * El cuerpo lleva el marcador {@code {code}}; la sustitución la hace el servicio que envía. Mismo patrón
 * que {@link SubscriptionEmailLabel}.
 */
public enum PaymentMethodEmailLabel {

    SUBJECT(new Translations("Código para eliminar tu método de pago · NX036",
            "Code to remove your payment method · NX036", "Código para remover o teu método de pagamento · NX036",
            "删除支付方式的验证码 · NX036", "Code pour supprimer votre moyen de paiement · NX036",
            "Code zum Entfernen deiner Zahlungsmethode · NX036",
            "Codice per rimuovere il tuo metodo di pagamento · NX036",
            "Code om je betaalmethode te verwijderen · NX036")), TITLE(
                    new Translations("Confirma la eliminación", "Confirm removal", "Confirma a remoção", "确认删除",
                            "Confirmer la suppression", "Entfernung bestätigen", "Conferma la rimozione",
                            "Verwijdering bevestigen")), BODY(
                                    new Translations(
                                            "Usa este código para eliminar tu método de pago: {code}. Caduca en 15 minutos. Si no lo has solicitado, ignora este correo.",
                                            "Use this code to remove your payment method: {code}. It expires in 15 minutes. If you didn't request it, ignore this email.",
                                            "Usa este código para remover o teu método de pagamento: {code}. Expira em 15 minutos. Se não o solicitaste, ignora este email.",
                                            "使用此验证码删除您的支付方式:{code}。15 分钟后失效。如果不是您本人操作,请忽略此邮件。",
                                            "Utilisez ce code pour supprimer votre moyen de paiement : {code}. Il expire dans 15 minutes. Si vous ne l'avez pas demandé, ignorez cet e-mail.",
                                            "Verwende diesen Code, um deine Zahlungsmethode zu entfernen: {code}. Er läuft in 15 Minuten ab. Falls du das nicht angefordert hast, ignoriere diese E-Mail.",
                                            "Usa questo codice per rimuovere il tuo metodo di pagamento: {code}. Scade tra 15 minuti. Se non l'hai richiesto, ignora questa email.",
                                            "Gebruik deze code om je betaalmethode te verwijderen: {code}. Verloopt over 15 minuten. Als je dit niet hebt aangevraagd, negeer deze e-mail."));

    private final Translations translations;

    PaymentMethodEmailLabel(Translations translations) {
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
