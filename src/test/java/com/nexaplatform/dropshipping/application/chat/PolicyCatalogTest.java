package com.nexaplatform.dropshipping.application.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyCatalogTest {

    @Test
    @DisplayName("El fichero de fábrica solo trae instrucciones: el catálogo queda vacío")
    void deFabricaEstaVacio() {
        PolicyCatalog catalogo = new PolicyCatalog();

        // Mientras nadie escriba hechos reales, el asistente no debe hablar de condiciones.
        // Cuando se rellenen las secciones, esta prueba fallará y habrá que ajustarla —a
        // propósito: publicar condiciones es una decisión, no un efecto secundario.
        assertTrue(catalogo.vacio());
        assertFalse(catalogo.hechos().contains("RELLENAR"));
    }
}
