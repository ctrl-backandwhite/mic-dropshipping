package com.nexaplatform.dropshipping.api.exception;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reglas del humanizador de errores: lo que ve el usuario nunca puede ser SQL crudo y el mensaje más
 * concreto disponible tiene que ganar al genérico.
 */
class Cov06ErrorMessagesTest {

    @Test
    void sinExcepcionDevuelveMensajeNeutro() {
        assertThat(ErrorMessages.humanize(null)).isEqualTo("No se pudo completar la operación.");
    }

    /**
     * El mensaje de negocio ya está redactado para la persona: gana aunque la causa raíz traiga un error
     * de base de datos mucho más "informativo". Si se invirtiera el orden, el usuario vería jerga SQL en
     * lugar de la validación que él mismo puede corregir.
     */
    @Test
    void elMensajeDeNegocioGanaAlErrorDeBaseDeDatos() {
        BusinessException negocio = new BusinessException("El nombre del grupo es obligatorio.");
        negocio.initCause(new SQLException("duplicate key value violates unique constraint \"users_email_key\""));

        assertThat(ErrorMessages.humanize(negocio)).isEqualTo("El nombre del grupo es obligatorio.");
    }

    /** El de negocio se busca en TODA la cadena de causas, no solo en la excepción de arriba. */
    @Test
    void encuentraElMensajeDeNegocioAunqueEsteEnvuelto() {
        RuntimeException envoltorio = new RuntimeException("org.hibernate.exception.ConstraintViolationException",
                new BusinessException("No hay saldo suficiente."));

        assertThat(ErrorMessages.humanize(envoltorio)).isEqualTo("No hay saldo suficiente.");
    }

    /**
     * Una excepción de negocio construida solo con código y detalle no tiene mensaje: no puede
     * "ganar" con un texto vacío, hay que seguir buscando una explicación de verdad.
     */
    @Test
    void unaExcepcionDeNegocioSinMensajeNoSeUsaComoTexto() {
        NotFoundException sinMensaje = new NotFoundException("CART_ITEM_UNAVAILABLE", List.of("abc"));

        assertThat(ErrorMessages.humanize(sinMensaje))
                .isEqualTo("No se pudo completar la operación por un error inesperado.");
    }

    /** Constraint con nombre conocido: se traduce a su mensaje propio, que es el más preciso. */
    @Test
    void traduceLaConstraintConNombreConocido() {
        SQLException e = new SQLException("ERROR: duplicate key value violates unique constraint \"users_email_key\"");

        assertThat(ErrorMessages.humanize(e)).isEqualTo("Ya existe un usuario con ese email.");
    }

    /** Duplicado sin constraint mapeada: se le dice al usuario QUÉ campo y con qué valor choca. */
    @Test
    void duplicadoSinMapearMuestraLaClaveYElValorEnConflicto() {
        SQLException e = new SQLException("ERROR: duplicate key value violates unique index\n"
                + "  Detail: Key (slug)=(camiseta-azul) already exists.");

        assertThat(ErrorMessages.humanize(e))
                .isEqualTo("Ya existe un registro con slug = camiseta-azul. Ese valor debe ser único.");
    }

    @Test
    void duplicadoSinDetalleCaeAlMensajeGenericoDeDuplicado() {
        SQLException e = new SQLException("ERROR: duplicate key value violates unique index");

        assertThat(ErrorMessages.humanize(e)).isEqualTo(
                "Ya existe un registro con esos datos (valor duplicado). Revisa los campos que deben ser únicos.");
    }

    @Test
    void faltaObligatorioNombraLaColumna() {
        SQLException e = new SQLException("ERROR: null value in column \"base_price\" violates not-null");

        assertThat(ErrorMessages.humanize(e)).isEqualTo("Falta un valor obligatorio en el campo 'base_price'.");
    }

    @Test
    void faltaObligatorioSinColumnaDaMensajeGenerico() {
        SQLException e = new SQLException("ERROR: not-null violation");

        assertThat(ErrorMessages.humanize(e)).isEqualTo("Falta un valor obligatorio.");
    }

    @Test
    void textoDemasiadoLargoIndicaElMaximoPermitido() {
        SQLException e = new SQLException("ERROR: value too long for type character varying(200)");

        assertThat(ErrorMessages.humanize(e))
                .isEqualTo("Un texto supera el largo máximo permitido (200 caracteres). Acórtalo.");
    }

    @Test
    void textoDemasiadoLargoSinLongitudNoInventaElNumero() {
        SQLException e = new SQLException("ERROR: value too long");

        assertThat(ErrorMessages.humanize(e)).isEqualTo("Un texto supera el largo máximo permitido. Acórtalo.");
    }

    @Test
    void claveForaneaSeExplicaComoReferenciaInexistente() {
        SQLException e = new SQLException("ERROR: insert violates foreign key restriction");

        assertThat(ErrorMessages.humanize(e)).isEqualTo(
                "Se hace referencia a un registro que no existe (categoría, proveedor o relación inválida).");
    }

    /** Un check con nombre NO mapeado no puede quedarse sin mensaje: cae al genérico de validación. */
    @Test
    void checkNoMapeadoCaeAlMensajeDeReglaDeValidacion() {
        SQLException e = new SQLException("ERROR: new row violates check constraint \"algo_raro_check\"");

        assertThat(ErrorMessages.humanize(e))
                .isEqualTo("Un valor no cumple una regla de validación. Revisa los campos del registro.");
    }

    @Test
    void formatoInvalidoSeExplicaComoValorMalFormado() {
        SQLException e = new SQLException("ERROR: invalid input syntax for type uuid: \"xxx\"");

        assertThat(ErrorMessages.humanize(e))
                .isEqualTo("Un valor tiene un formato inválido (número, fecha o identificador mal formado).");
    }

    @Test
    void falloDeLoteSinConstraintReconocidaDaMensajeNeutro() {
        SQLException e = new SQLException("could not execute batch");

        assertThat(ErrorMessages.humanize(e)).isEqualTo(
                "No se pudo guardar el registro por un conflicto de datos. Revisa los valores duplicados u obligatorios.");
    }

    /** Ningún patrón encaja: mensaje neutro, y sobre todo NADA del texto técnico original. */
    @Test
    void errorDesconocidoNuncaFiltraElTextoTecnico() {
        SQLException e = new SQLException("org.postgresql.util.PSQLException: FATAL: terminating connection");

        String humano = ErrorMessages.humanize(e);

        assertThat(humano)
                .isEqualTo("No se pudo guardar por un conflicto de datos. Revisa los valores e inténtalo de nuevo.")
                .doesNotContain("PSQLException");
    }

    @Test
    void causaRaizSinMensajeDaErrorInesperado() {
        RuntimeException e = new RuntimeException(new IllegalStateException((String) null));

        assertThat(ErrorMessages.humanize(e)).isEqualTo("No se pudo completar la operación por un error inesperado.");
    }
}
