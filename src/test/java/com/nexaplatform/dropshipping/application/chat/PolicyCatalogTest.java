package com.nexaplatform.dropshipping.application.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El catálogo de hechos publicables.
 *
 * <p>Lo que hay aquí dentro es lo único que el asistente puede afirmar sobre condiciones, y cada
 * frase compromete a la empresa ante un cliente. Por eso se prueba: que el contenido llegue entero,
 * que las notas para quien edita el fichero NO lleguen, y que estén las condiciones que más se
 * preguntan.
 */
class PolicyCatalogTest {

    private final PolicyCatalog catalogo = new PolicyCatalog();

    @Test
    @DisplayName("Hay hechos publicados: el asistente puede responder sobre condiciones")
    void hayHechos() {
        assertFalse(catalogo.vacio());
    }

    @Test
    @DisplayName("Están las condiciones que más se preguntan")
    void lasQueMasSePreguntan() {
        String hechos = catalogo.hechos();

        // Desistimiento: el plazo legal, con su cifra exacta.
        assertTrue(hechos.contains("catorce (14) días naturales"));
        // Aduanas: la promesa central del DDP, que es la que evita la sorpresa en la entrega.
        assertTrue(hechos.contains("no recibe ningún cargo adicional del transportista"));
        assertTrue(hechos.contains("150 EUR"));
        // Y lo que el asistente debe decir de sí mismo.
        assertTrue(hechos.contains("proveedor externo de inteligencia artificial"));
        assertTrue(hechos.contains("pueden contener errores"));
    }

    @Test
    @DisplayName("Las notas para quien edita el fichero no llegan al cliente")
    void sinNotasInternas() {
        String hechos = catalogo.hechos();

        // Los comentarios son instrucciones para quien mantiene el fichero, no hechos que contar a
        // nadie: si se colaran, el asistente hablaría de «pendiente de completar» a un cliente.
        assertFalse(hechos.contains("PENDIENTE"));
        assertFalse(hechos.contains("<!--"));
        // Y la cabecera de instrucciones tampoco: los hechos empiezan en la primera sección.
        assertFalse(hechos.contains("Se inyecta entero"));
    }
}
