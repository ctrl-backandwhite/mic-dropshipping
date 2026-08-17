package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UnserviceableZoneEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UnserviceableZoneRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Destinos a los que el transportista no entrega dentro de un país que sí cubre.
 *
 * <p>La cobertura se comprobaba solo por país, y YunExpress excluye zonas concretas: en España,
 * Baleares, Canarias, Ceuta y Melilla. El resultado era que alguien de Palma completaba el pedido,
 * pagaba, y el envío no se podía despachar. Esto se comprueba ANTES de cobrar.
 */
class UnserviceableZoneServiceTest {

    private UnserviceableZoneRepository repository;
    private UnserviceableZoneService service;

    private static UnserviceableZoneEntity rango(String pais, String desde, String hasta) {
        UnserviceableZoneEntity z = new UnserviceableZoneEntity();
        z.setCountryCode(pais);
        z.setPostalFrom(desde);
        z.setPostalTo(hasta);
        return z;
    }

    @BeforeEach
    void setUp() {
        repository = mock(UnserviceableZoneRepository.class);
        service = new UnserviceableZoneService(repository);
        List<UnserviceableZoneEntity> espana = List.of(
                rango("ES", "07000", "07999"),   // Baleares
                rango("ES", "35000", "35999"),   // Las Palmas
                rango("ES", "38000", "38999"),   // Santa Cruz de Tenerife
                rango("ES", "51000", "52999"));  // Ceuta y Melilla
        // El repositorio real ignora mayúsculas (findBy...IgnoreCase); el mock no, así que se stubean
        // las dos grafías para poder probar que el servicio no ensucia el código de país.
        when(repository.findByCountryCodeIgnoreCase("ES")).thenReturn(espana);
        when(repository.findByCountryCodeIgnoreCase("es")).thenReturn(espana);
    }

    @Test
    @DisplayName("las islas y las ciudades autónomas no son destino válido")
    void bloqueaLosTerritoriosExcluidos() {
        assertThat(service.isUnserviceable("ES", "07001")).isTrue();   // Palma
        assertThat(service.isUnserviceable("ES", "35001")).isTrue();   // Las Palmas
        assertThat(service.isUnserviceable("ES", "38001")).isTrue();   // Santa Cruz
        assertThat(service.isUnserviceable("ES", "51001")).isTrue();   // Ceuta
        assertThat(service.isUnserviceable("ES", "52001")).isTrue();   // Melilla
    }

    @Test
    @DisplayName("la península se sirve con normalidad")
    void noBloqueaElRestoDelPais() {
        assertThat(service.isUnserviceable("ES", "28013")).isFalse();  // Madrid
        assertThat(service.isUnserviceable("ES", "08001")).isFalse();  // Barcelona
        assertThat(service.isUnserviceable("ES", "50004")).isFalse();  // Zaragoza
    }

    @Test
    @DisplayName("los bordes exactos del rango quedan dentro")
    void losBordesSonInclusivos() {
        assertThat(service.isUnserviceable("ES", "07000")).isTrue();
        assertThat(service.isUnserviceable("ES", "07999")).isTrue();
        assertThat(service.isUnserviceable("ES", "06999")).isFalse();
        assertThat(service.isUnserviceable("ES", "08000")).isFalse();
    }

    @Test
    @DisplayName("el código postal se normaliza antes de comparar")
    void toleraEspaciosYFormato() {
        // El cliente escribe como quiere: "07 001", " 07001 ".
        assertThat(service.isUnserviceable("ES", " 07001 ")).isTrue();
        assertThat(service.isUnserviceable("ES", "07 001")).isTrue();
        assertThat(service.isUnserviceable("es", "07001")).isTrue();
    }

    @Test
    @DisplayName("sin código postal no se bloquea: no se puede afirmar que sea zona excluida")
    void sinCodigoPostalNoSeBloquea() {
        // Bloquear por falta de dato dejaría sin comprar a países donde el CP no es obligatorio.
        assertThat(service.isUnserviceable("ES", null)).isFalse();
        assertThat(service.isUnserviceable("ES", "   ")).isFalse();
    }

    @Test
    @DisplayName("un país sin exclusiones no consulta dos veces ni bloquea")
    void unPaisSinExclusionesNoBloquea() {
        when(repository.findByCountryCodeIgnoreCase("DE")).thenReturn(List.of());

        assertThat(service.isUnserviceable("DE", "10115")).isFalse();
    }

    @Test
    @DisplayName("sin país no se consulta la tabla")
    void sinPaisNiSeConsulta() {
        assertThat(service.isUnserviceable(null, "07001")).isFalse();
        verify(repository, never()).findByCountryCodeIgnoreCase(anyString());
    }

    @Test
    @DisplayName("un código postal no numérico no rompe la comparación")
    void toleraCodigosNoNumericos() {
        // Reino Unido y Países Bajos usan formatos alfanuméricos; aquí solo hay rangos numéricos, así
        // que lo que no se puede comparar simplemente no bloquea.
        assertThat(service.isUnserviceable("ES", "SW1A1AA")).isFalse();
    }
}
