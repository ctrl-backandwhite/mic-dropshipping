package com.nexaplatform.dropshipping.domain.enums;

public enum UserRole {
    /**
     * Revisa el MATERIAL GRÁFICO de las fichas: galería, fotos de la descripción, fotos de color y el
     * vídeo, más el enlace a la oferta de origen para poder cotejarlas contra el proveedor.
     *
     * <p>No es un administrador recortado: no ve el desglose de precio —coste, margen y las dos bolsas
     * de subvención—, no marca «Verificado» —eso lo decide el dueño— y no entra al panel. Trabaja
     * dentro de la propia ficha del escaparate, que es donde se ven las fotos como las ve quien compra.
     */
    USER, PARTNER, OPERATOR, REVIEWER, ADMIN;

    public String authority() {
        return "ROLE_" + name();
    }
}
