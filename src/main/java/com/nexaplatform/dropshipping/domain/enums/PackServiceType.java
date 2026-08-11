package com.nexaplatform.dropshipping.domain.enums;

/**
 * Tipo de servicio de la orden de re-empaquetado en Yunfulfillment.
 *
 * <p>El código que viaja en el fichero de importación es el texto CHINO exacto de la plantilla oficial:
 * su OMS lo compara literalmente y un valor traducido se rechaza con «The registered information does
 * not match the selected service type».
 *
 * <p>Las restricciones no son de forma sino de negocio, y están tomadas de la hoja «服务类型» de la
 * plantilla: hay servicios que no admiten nota y otros que no admiten valor añadido. Mandar un campo
 * que el servicio no acepta invalida la fila entera.
 */
public enum PackServiceType {

    /**
     * Solo pegar la etiqueta. Para bultos que llegaron ya etiquetados por error: ni admite servicios de
     * valor añadido ni nota.
     */
    LABEL_ONLY("仅贴标", 1_00L, false, false),

    /**
     * Quitar el embalaje del proveedor y poner uno neutro. Es el caso normal de un pedido de un solo
     * proveedor. Admite valor añadido, pero NO nota: «只更换包装发货».
     */
    REPACKAGING("更换包装", 2_00L, true, false),

    /** Un bulto entrante que se reparte en varios envíos YT distintos. */
    SPLIT("一个快递多个YT单", 2_00L, true, true),

    /** Varios bultos entrantes que se consolidan bajo un único YT: el pedido multiproveedor. */
    CONSOLIDATE("多个快递一个YT单", 5_00L, true, true),

    /** Embalaje o manipulación especial. Es el único que admite instrucciones libres detalladas. */
    CUSTOM("定制打包", 5_00L, true, true);

    private final String code;
    private final long priceCnyCents;
    private final boolean allowsValueAdded;
    private final boolean allowsRemarks;

    PackServiceType(String code, long priceCnyCents, boolean allowsValueAdded, boolean allowsRemarks) {
        this.code = code;
        this.priceCnyCents = priceCnyCents;
        this.allowsValueAdded = allowsValueAdded;
        this.allowsRemarks = allowsRemarks;
    }

    /** Texto chino exacto que espera el OMS. */
    public String code() {
        return code;
    }

    /** Tarifa por orden, en céntimos de CNY (cuadro de tarifas de Yunfulfillment). */
    public long priceCnyCents() {
        return priceCnyCents;
    }

    public boolean allowsValueAdded() {
        return allowsValueAdded;
    }

    public boolean allowsRemarks() {
        return allowsRemarks;
    }

    /**
     * El servicio que corresponde según cuántos bultos entren.
     *
     * <p>Un solo bulto es un re-empaquetado a 2 CNY; varios hay que consolidarlos y cuesta 5. Elegir
     * consolidación con un único bulto funciona, pero paga 3 CNY de más en cada pedido.
     */
    public static PackServiceType forIncomingParcels(int parcels) {
        return parcels > 1 ? CONSOLIDATE : REPACKAGING;
    }
}
