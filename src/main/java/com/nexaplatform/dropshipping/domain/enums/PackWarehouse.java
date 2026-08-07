package com.nexaplatform.dropshipping.domain.enums;

import java.util.Locale;

/**
 * Almacenes chinos de Yunfulfillment donde recibe la mercancía comprada en 1688.
 *
 * <p>La dirección se guarda en chino y completa porque va a pegarse tal cual en el formulario de envío
 * de 1688: el proveedor la lee un transportista chino, no nosotros. El aviso «请勿放丰巢» (no dejar en
 * taquilla automática) forma parte de la dirección a propósito — si el mensajero la deja en una
 * taquilla, el almacén no la recoge.
 *
 * <p>El código de cliente hay que añadirlo al final y también al destinatario: sin él el almacén recibe
 * un bulto que no sabe de quién es. La propia plantilla lo advierte: «地址选择错误，仓库无法查询操作».
 */
public enum PackWarehouse {

    /** 东莞仓 (Dongguan, Guangdong): el de referencia para proveedores del sur. */
    CNCHASHAN("东莞仓", "广东省东莞市茶山镇超横路2号2栋4楼仓储部(请勿放丰巢)", "523392",
            "0769-83369816"),

    /** 嘉善仓 (Jiashan, Zhejiang): para proveedores de la zona de Yiwu y Hangzhou. */
    CNJIASHAN("嘉善仓", "浙江省嘉兴市嘉善县魏塘街道嘉魏路9号镝擎嘉善产业园二楼云途物流3号仓1213号", "314100",
            "13085621862"),

    /** Almacén de pruebas del OMS: permite ensayar el flujo entero sin mercancía real. */
    TESTSTORE("测试仓", "", "", "");

    private final String nameZh;
    private final String address;
    private final String postalCode;
    private final String phone;

    PackWarehouse(String nameZh, String address, String postalCode, String phone) {
        this.nameZh = nameZh;
        this.address = address;
        this.postalCode = postalCode;
        this.phone = phone;
    }

    public String nameZh() {
        return nameZh;
    }

    public String postalCode() {
        return postalCode;
    }

    public String phone() {
        return phone;
    }

    /** Destinatario que espera el almacén: «云途» seguido del código de cliente, sin espacio. */
    public String consignee(String customerCode) {
        return "云途" + customerCode;
    }

    /** La dirección completa con el código de cliente pegado al final, como exige el almacén. */
    public String fullAddress(String customerCode) {
        return address.isEmpty() ? "" : address + " " + customerCode;
    }

    /** Resuelve el código del almacén sin reventar si viene vacío o desconocido. */
    public static PackWarehouse fromCode(String code) {
        if (code == null || code.isBlank()) {
            return CNCHASHAN;
        }
        try {
            return valueOf(code.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return CNCHASHAN;
        }
    }
}
