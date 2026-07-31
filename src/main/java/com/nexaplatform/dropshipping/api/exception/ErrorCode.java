package com.nexaplatform.dropshipping.api.exception;

import java.util.List;
import java.util.Locale;

/**
 * Mensajes de error localizados a los 8 idiomas soportados, resueltos por el CÓDIGO estable que lleva
 * cada excepción. Es la ÚNICA fuente de verdad de los textos de error: el {@link GlobalExceptionHandler}
 * traduce el mensaje según el idioma de la petición ({@code LocaleHolder}). Escalable: para localizar un
 * error basta con lanzarlo con un código que exista aquí; añadir un error = una constante con sus 8
 * traducciones, en un solo sitio. Mismo patrón que {@code InvoiceLabel}/{@code OrderEmailLabel}.
 *
 * <p>Los códigos por defecto de cada tipo de excepción (BR001, ENF001, AE001, DM001, CF001, RL001,
 * IS001) tienen un mensaje GENÉRICO localizado, para que TODO error salga en el idioma del usuario aunque
 * no tenga un código específico.
 */
public enum ErrorCode {

    // ---------- genéricos por tipo de excepción ----------
    BR001("No se pudo completar la operación. Revisa los datos e inténtalo de nuevo.",
            "The operation could not be completed. Please check your input and try again.",
            "Não foi possível concluir a operação. Verifique os dados e tente novamente.",
            "无法完成操作。请检查输入后重试。",
            "L'opération n'a pas pu être effectuée. Vérifiez vos informations et réessayez.",
            "Der Vorgang konnte nicht abgeschlossen werden. Bitte überprüfe deine Eingaben und versuche es erneut.",
            "Impossibile completare l'operazione. Controlla i dati e riprova.",
            "De bewerking kon niet worden voltooid. Controleer je gegevens en probeer het opnieuw."),
    ENF001("No se encontró el recurso solicitado.", "The requested item was not found.",
            "O recurso solicitado não foi encontrado.", "未找到请求的资源。",
            "La ressource demandée est introuvable.", "Die angeforderte Ressource wurde nicht gefunden.",
            "La risorsa richiesta non è stata trovata.", "De gevraagde bron is niet gevonden."),
    AE001("Hay datos no válidos. Revisa los campos e inténtalo de nuevo.",
            "Some data is invalid. Please review the fields and try again.",
            "Alguns dados são inválidos. Reveja os campos e tente novamente.",
            "部分数据无效。请检查字段后重试。",
            "Certaines données sont invalides. Vérifiez les champs et réessayez.",
            "Einige Daten sind ungültig. Überprüfe die Felder und versuche es erneut.",
            "Alcuni dati non sono validi. Controlla i campi e riprova.",
            "Sommige gegevens zijn ongeldig. Controleer de velden en probeer het opnieuw."),
    DM001("El recurso ya existe o hay un conflicto con el estado actual.",
            "The resource already exists or conflicts with the current state.",
            "O recurso já existe ou há um conflito com o estado atual.", "资源已存在或与当前状态冲突。",
            "La ressource existe déjà ou est en conflit avec l'état actuel.",
            "Die Ressource existiert bereits oder steht im Konflikt mit dem aktuellen Status.",
            "La risorsa esiste già o è in conflitto con lo stato attuale.",
            "De bron bestaat al of is in conflict met de huidige status."),
    CF001("El recurso ya existe o hay un conflicto con el estado actual.",
            "The resource already exists or conflicts with the current state.",
            "O recurso já existe ou há um conflito com o estado atual.", "资源已存在或与当前状态冲突。",
            "La ressource existe déjà ou est en conflit avec l'état actuel.",
            "Die Ressource existiert bereits oder steht im Konflikt mit dem aktuellen Status.",
            "La risorsa esiste già o è in conflitto con lo stato attuale.",
            "De bron bestaat al of is in conflict met de huidige status."),
    RL001("Demasiadas solicitudes. Espera unos segundos e inténtalo de nuevo.",
            "Too many requests. Please wait a few seconds and try again.",
            "Demasiados pedidos. Aguarde alguns segundos e tente novamente.", "请求过于频繁。请稍等几秒后重试。",
            "Trop de requêtes. Patientez quelques secondes et réessayez.",
            "Zu viele Anfragen. Bitte warte einige Sekunden und versuche es erneut.",
            "Troppe richieste. Attendi qualche secondo e riprova.",
            "Te veel verzoeken. Wacht enkele seconden en probeer het opnieuw."),
    IS001("Ocurrió un error inesperado. Inténtalo de nuevo en unos minutos.",
            "An unexpected error occurred. Please try again in a few minutes.",
            "Ocorreu um erro inesperado. Tente novamente dentro de alguns minutos.", "发生意外错误。请稍后几分钟再试。",
            "Une erreur inattendue s'est produite. Réessayez dans quelques minutes.",
            "Ein unerwarteter Fehler ist aufgetreten. Bitte versuche es in einigen Minuten erneut.",
            "Si è verificato un errore imprevisto. Riprova tra qualche minuto.",
            "Er is een onverwachte fout opgetreden. Probeer het over een paar minuten opnieuw."),

    // ---------- específicos ----------
    ORDER_NOT_CANCELLABLE(
            "Este pedido ya ha sido procesado y no puede cancelarse. Si deseas devolverlo, contáctanos cuando lo recibas y gestionaremos la devolución.",
            "This order has already been processed and can no longer be cancelled. If you'd like to return it, please contact us once you receive it and we'll arrange the return.",
            "Este pedido já foi processado e não pode ser cancelado. Se desejar devolvê-lo, contacte-nos quando o receber e trataremos da devolução.",
            "此订单已处理,无法取消。如需退货,请在收到后联系我们,我们将为您办理退货。",
            "Cette commande a déjà été traitée et ne peut plus être annulée. Si vous souhaitez la retourner, contactez-nous dès réception et nous organiserons le retour.",
            "Diese Bestellung wurde bereits bearbeitet und kann nicht mehr storniert werden. Wenn du sie zurückgeben möchtest, kontaktiere uns nach Erhalt und wir kümmern uns um die Rücksendung.",
            "Questo ordine è già stato elaborato e non può più essere annullato. Se desideri restituirlo, contattaci una volta ricevuto e gestiremo il reso.",
            "Deze bestelling is al verwerkt en kan niet meer worden geannuleerd. Als je hem wilt retourneren, neem dan contact met ons op zodra je hem hebt ontvangen en wij regelen de retour."),
    ORDER_NOT_FOUND("No se encontró el pedido.", "The order could not be found.", "Não foi possível encontrar o pedido.",
            "找不到该订单。", "Commande introuvable.", "Bestellung nicht gefunden.", "Ordine non trovato.",
            "Bestelling niet gevonden."),
    WALLET_INSUFFICIENT_BALANCE("No tienes saldo suficiente en tu billetera.",
            "You don't have enough balance in your wallet.", "Não tem saldo suficiente na sua carteira.",
            "您的钱包余额不足。", "Vous n'avez pas assez de solde dans votre portefeuille.",
            "Du hast nicht genügend Guthaben in deinem Wallet.", "Non hai saldo sufficiente nel tuo wallet.",
            "Je hebt niet genoeg saldo in je wallet."),
    CART_EMPTY("Tu carrito debe tener al menos un producto.", "Your cart must have at least one product.",
            "O seu carrinho deve ter pelo menos um produto.", "您的购物车至少需要一件商品。",
            "Votre panier doit contenir au moins un produit.", "Dein Warenkorb muss mindestens ein Produkt enthalten.",
            "Il tuo carrello deve contenere almeno un prodotto.", "Je winkelwagen moet minstens één product bevatten."),
    SHIPPING_ADDRESS_REQUIRED("Debes indicar una dirección de envío.", "A shipping address is required.",
            "É necessário indicar uma morada de envio.", "需要填写收货地址。", "Une adresse de livraison est requise.",
            "Eine Lieferadresse ist erforderlich.", "È necessario un indirizzo di spedizione.",
            "Een verzendadres is verplicht."),
    PLAN_COUNTRY_REQUIRED("Selecciona un país en tu perfil antes de contratar un plan.",
            "Select a country in your profile before subscribing to a plan.",
            "Selecione um país no seu perfil antes de contratar um plano.", "订阅套餐前,请先在个人资料中选择国家/地区。",
            "Sélectionnez un pays dans votre profil avant de souscrire à un plan.",
            "Wähle ein Land in deinem Profil, bevor du einen Plan abschließt.",
            "Seleziona un paese nel tuo profilo prima di sottoscrivere un piano.",
            "Selecteer een land in je profiel voordat je een abonnement afsluit."),
    PLAN_CARD_REQUIRED("Añade una tarjeta en tu perfil antes de contratar un plan.",
            "Add a card in your profile before subscribing to a plan.",
            "Adicione um cartão no seu perfil antes de contratar um plano.", "订阅套餐前,请先在个人资料中添加银行卡。",
            "Ajoutez une carte dans votre profil avant de souscrire à un plan.",
            "Füge eine Karte in deinem Profil hinzu, bevor du einen Plan abschließt.",
            "Aggiungi una carta nel tuo profilo prima di sottoscrivere un piano.",
            "Voeg een kaart toe in je profiel voordat je een abonnement afsluit."),
    FREE_TRIAL_ALREADY_USED("Ya has utilizado tu mes de prueba gratis. Elige un plan de pago.",
            "You have already used your free trial month. Please choose a paid plan.",
            "Já utilizou o seu mês de teste grátis. Escolha um plano pago.",
            "您已使用过免费试用月。请选择付费套餐。",
            "Vous avez déjà utilisé votre mois d'essai gratuit. Choisissez un plan payant.",
            "Du hast deinen kostenlosen Probemonat bereits genutzt. Bitte wähle einen kostenpflichtigen Plan.",
            "Hai già utilizzato il tuo mese di prova gratuito. Scegli un piano a pagamento.",
            "Je hebt je gratis proefmaand al gebruikt. Kies een betaald abonnement."),
    CART_ITEM_UNAVAILABLE(
            "Uno de los productos de tu carrito ya no está disponible porque el catálogo se actualizó. Quítalo y vuelve a añadirlo desde el catálogo.",
            "One of the products in your cart is no longer available because the catalog was updated. Remove it and add it again from the catalog.",
            "Um dos produtos do seu carrinho já não está disponível porque o catálogo foi atualizado. Remova-o e adicione-o novamente a partir do catálogo.",
            "您购物车中的某件商品已不再可用，因为目录已更新。请将其移除并从目录中重新添加。",
            "L'un des produits de votre panier n'est plus disponible car le catalogue a été mis à jour. Retirez-le et ajoutez-le à nouveau depuis le catalogue.",
            "Eines der Produkte in deinem Warenkorb ist nicht mehr verfügbar, da der Katalog aktualisiert wurde. Entferne es und füge es erneut aus dem Katalog hinzu.",
            "Uno dei prodotti nel carrello non è più disponibile perché il catalogo è stato aggiornato. Rimuovilo e aggiungilo di nuovo dal catalogo.",
            "Een van de producten in je winkelwagen is niet meer beschikbaar omdat de catalogus is bijgewerkt. Verwijder het en voeg het opnieuw toe vanuit de catalogus."),
    DELETION_CODE_INVALID("El código de eliminación no es válido o ha expirado.",
            "The deletion code is invalid or has expired.",
            "O código de eliminação é inválido ou expirou.",
            "删除验证码无效或已过期。",
            "Le code de suppression est invalide ou a expiré.",
            "Der Löschcode ist ungültig oder abgelaufen.",
            "Il codice di eliminazione non è valido o è scaduto.",
            "De verwijdercode is ongeldig of verlopen.");

    /**
     * Orden EXACTO en el que cada constante declara sus textos. Añadir un idioma es añadirlo aquí y en
     * todas las constantes.
     */
    private enum Lang {
        ES, EN, PT, ZH, FR, DE, IT, NL;

        /** Idioma del código ISO recibido, con el castellano como respaldo. */
        static Lang of(String code) {
            for (Lang candidate : values()) {
                if (candidate.name().equalsIgnoreCase(code)) {
                    return candidate;
                }
            }
            return ES;
        }
    }

    private final List<String> messages;

    /**
     * Los textos llegan como lista variable en el orden de {@link Lang}: ocho parámetros {@code String}
     * seguidos son justo el caso en el que intercambiar dos al declarar una constante compila igual y el
     * error sale en el idioma equivocado. Si a una constante le faltara un idioma, la clase falla al
     * cargarse en vez de devolver un mensaje vacío al usuario.
     */
    ErrorCode(String... messages) {
        if (messages.length != Lang.values().length) {
            throw new IllegalArgumentException(
                    "Cada ErrorCode debe declarar " + Lang.values().length + " traducciones");
        }
        this.messages = List.of(messages);
    }

    public String of(String lang) {
        String code = lang == null ? "es" : lang.trim().toLowerCase(Locale.ROOT).split("[-_]")[0];
        return messages.get(Lang.of(code).ordinal());
    }

    /** Mensaje localizado para el {@code code} indicado, o {@code null} si el código no está catalogado. */
    public static String localize(String code, String lang) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return valueOf(code.trim()).of(lang);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
