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

    // ---------- seguridad ----------
    /*
     * Los errores de acceso salían SIEMPRE en inglés: el manejador escribía «Unauthorized» a mano y aquí
     * no había ningún código de seguridad, así que `localize` devolvía null y pasaba el literal tal cual.
     * Quien entra con la contraseña mal en la aplicación, con el idioma en español, leía «Unauthorized».
     *
     * El texto sigue siendo el MISMO para todos los motivos —contraseña mala, cuenta sin activar,
     * bloqueada o inexistente—: traducirlo no puede convertirlo en un delator de qué cuentas existen.
     */
    SE001("No tienes permiso para hacer esto.", "You do not have permission to do this.",
            "Não tens permissão para fazer isto.", "您没有执行此操作的权限。",
            "Vous n'avez pas l'autorisation d'effectuer cette action.",
            "Du hast keine Berechtigung, das zu tun.", "Non hai i permessi per farlo.",
            "Je hebt geen toestemming om dit te doen."),
    SE002("El correo o la contraseña no son correctos.", "The email or password is incorrect.",
            "O e-mail ou a palavra-passe não estão corretos.", "邮箱或密码不正确。",
            "L'adresse e-mail ou le mot de passe est incorrect.",
            "E-Mail-Adresse oder Passwort sind nicht korrekt.", "L'email o la password non sono corretti.",
            "Het e-mailadres of wachtwoord is onjuist."),
    MFA_REQUIRED("Introduce el código de verificación de tu aplicación de autenticación.",
            "Enter the verification code from your authenticator app.",
            "Introduz o código de verificação da tua aplicação de autenticação.",
            "请输入身份验证器应用中的验证码。",
            "Saisissez le code de vérification de votre application d'authentification.",
            "Gib den Bestätigungscode aus deiner Authenticator-App ein.",
            "Inserisci il codice di verifica della tua app di autenticazione.",
            "Voer de verificatiecode uit je authenticator-app in."),
    MFA_INVALID("El código de verificación no es correcto o ha caducado.",
            "The verification code is incorrect or has expired.",
            "O código de verificação não está correto ou expirou.", "验证码不正确或已过期。",
            "Le code de vérification est incorrect ou a expiré.",
            "Der Bestätigungscode ist falsch oder abgelaufen.",
            "Il codice di verifica non è corretto o è scaduto.",
            "De verificatiecode is onjuist of verlopen."),

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
    REFUND_NOT_POSSIBLE(
            "No hemos podido devolver el importe a tu método de pago original. Puedes cancelar recibiendo el dinero en tu monedero, o escríbenos y lo resolvemos.",
            "We couldn't refund the amount to your original payment method. You can cancel and receive the money in your wallet instead, or contact us and we'll sort it out.",
            "Não conseguimos devolver o valor ao seu método de pagamento original. Pode cancelar recebendo o dinheiro na sua carteira, ou contacte-nos e resolvemos.",
            "无法将款项退回您的原支付方式。您可以选择将金额退回钱包,或联系我们协助处理。",
            "Nous n'avons pas pu rembourser le montant sur votre moyen de paiement d'origine. Vous pouvez annuler en recevant l'argent sur votre portefeuille, ou nous contacter et nous nous en occupons.",
            "Wir konnten den Betrag nicht auf dein ursprüngliches Zahlungsmittel erstatten. Du kannst stattdessen stornieren und das Geld in deinem Wallet erhalten, oder schreib uns und wir kümmern uns darum.",
            "Non siamo riusciti a rimborsare l'importo sul tuo metodo di pagamento originale. Puoi annullare ricevendo il denaro nel tuo wallet, oppure scrivici e lo risolviamo.",
            "We konden het bedrag niet terugstorten naar je oorspronkelijke betaalmethode. Je kunt annuleren en het geld in je wallet ontvangen, of neem contact met ons op en wij lossen het op."),
    ORDER_NOT_FOUND("No se encontró el pedido.", "The order could not be found.", "Não foi possível encontrar o pedido.",
            "找不到该订单。", "Commande introuvable.", "Bestellung nicht gefunden.", "Ordine non trovato.",
            "Bestelling niet gevonden."),
    WALLET_INSUFFICIENT_BALANCE("No tienes saldo suficiente en tu billetera.",
            "You don't have enough balance in your wallet.", "Não tem saldo suficiente na sua carteira.",
            "您的钱包余额不足。", "Vous n'avez pas assez de solde dans votre portefeuille.",
            "Du hast nicht genügend Guthaben in deinem Wallet.", "Non hai saldo sufficiente nel tuo wallet.",
            "Je hebt niet genoeg saldo in je wallet."),
    MOQ_NOT_REACHED("No llegas al pedido mínimo de uno de los productos. Puedes completarlo mezclando colores o tallas del mismo producto.",
            "You haven't reached the minimum order for one of the products. You can make it up by mixing colours or sizes of the same product.",
            "Não atingiu a encomenda mínima de um dos produtos. Pode completá-la misturando cores ou tamanhos do mesmo produto.",
            "其中一件商品未达到起订量。可以用同一商品的不同颜色或尺码凑单。",
            "Vous n'atteignez pas la commande minimale de l'un des produits. Vous pouvez la compléter en mélangeant couleurs ou tailles du même produit.",
            "Bei einem der Produkte ist die Mindestbestellmenge nicht erreicht. Du kannst sie mit anderen Farben oder Größen desselben Produkts auffüllen.",
            "Non raggiungi l'ordine minimo di uno dei prodotti. Puoi completarlo mescolando colori o taglie dello stesso prodotto.",
            "Je haalt de minimale bestelhoeveelheid van een van de producten niet. Je kunt die aanvullen met andere kleuren of maten van hetzelfde product."),
    PURCHASE_NOT_EXPORTED(
            "Descarga antes el fichero de re-empaquetado y súbelo a Yunfulfillment: el número de la orden lo devuelve el OMS al importarlo.",
            "Download the repacking file first and upload it to Yunfulfillment: the order number is returned by the OMS on import.",
            "Descarregue primeiro o ficheiro de reembalagem e carregue-o no Yunfulfillment: o número da ordem é devolvido pelo OMS ao importar.",
            "请先下载重新打包文件并上传到 Yunfulfillment：订单号由 OMS 在导入时返回。",
            "Téléchargez d'abord le fichier de réemballage et importez-le dans Yunfulfillment : le numéro de commande est renvoyé par l'OMS à l'import.",
            "Lade zuerst die Umpack-Datei herunter und spiele sie in Yunfulfillment ein: Die Auftragsnummer gibt das OMS beim Import zurück.",
            "Scarica prima il file di reimballaggio e caricalo su Yunfulfillment: il numero dell'ordine lo restituisce l'OMS all'importazione.",
            "Download eerst het herverpakkingsbestand en upload het naar Yunfulfillment: het ordernummer geeft het OMS terug bij de import."),
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
    FREE_TRIAL_ALREADY_USED("Ya has utilizado tu prueba gratis de 15 días. Elige un plan de pago.",
            "You have already used your 15-day free trial. Please choose a paid plan.",
            "Já utilizaste a tua avaliação gratuita de 15 dias. Escolhe um plano pago.",
            "你已经使用过 15 天免费试用。请选择一个付费套餐。",
            "Vous avez déjà utilisé votre essai gratuit de 15 jours. Choisissez un forfait payant.",
            "Du hast deine 15-tägige kostenlose Testphase bereits genutzt. Bitte wähle einen kostenpflichtigen Tarif.",
            "Hai già utilizzato la tua prova gratuita di 15 giorni. Scegli un piano a pagamento.",
            "Je hebt je gratis proefperiode van 15 dagen al gebruikt. Kies een betaald abonnement."),
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
            "De verwijdercode is ongeldig of verlopen."),
    PRODUCT_SOURCE_URL_INVALID("El enlace de origen no es válido: debe ser una dirección http(s) de 1688 o Alibaba.",
            "The source link is not valid: it must be an http(s) address from 1688 or Alibaba.",
            "A ligação de origem não é válida: tem de ser um endereço http(s) do 1688 ou da Alibaba.",
            "来源链接无效：必须是 1688 或 Alibaba 的 http(s) 网址。",
            "Le lien d'origine n'est pas valide : il doit s'agir d'une adresse http(s) de 1688 ou Alibaba.",
            "Der Herkunftslink ist ungültig: Es muss eine http(s)-Adresse von 1688 oder Alibaba sein.",
            "Il link di origine non è valido: deve essere un indirizzo http(s) di 1688 o Alibaba.",
            "De bronlink is niet geldig: het moet een http(s)-adres van 1688 of Alibaba zijn.");

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
