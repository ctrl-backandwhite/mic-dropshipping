package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService.DutyParcel;
import com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService.Line;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CarrierChannelLimitEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CarrierChannelLimitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * El recuento de partidas arancelarias reparte los bultos con el límite del CANAL y del PAÍS.
 *
 * <p>No es una duplicación del reparto del despacho: el derecho temporal de 3 EUR de la Unión Europea se
 * cobra por línea de declaración <b>dentro de cada bulto</b>, así que contar con menos bultos de los que
 * de verdad van a salir cobra de menos, y esa diferencia la pone el comercio al despachar. Mientras el
 * peso máximo fue un escalar, el mismo carrito contaba igual para España (30 kg) que para Dinamarca
 * (15 kg), donde va a viajar partido en dos declaraciones y no en una.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CustomsDutyLinesLimitePorCanalTest {

    private static final String ROPA = "FZZXR";

    @Mock
    private CarrierChannelLimitRepository limitRepository;

    private CustomsDutyLinesService service;

    @BeforeEach
    void sembrarLosLimitesDeLaLineaDeRopa() {
        CarrierChannelLimitService limites = new CarrierChannelLimitService(limitRepository);
        ReflectionTestUtils.setField(limites, "maxParcelWeightGramsGlobal", 0);
        ReflectionTestUtils.setField(limites, "volumetricDivisorGlobal", 6000);
        fila(ROPA, "*", 30000);
        fila(ROPA, "DK", 15000);

        service = new CustomsDutyLinesService(limites);
        // Sin topes de valor ni de unidades: lo que se mide aquí es el peso.
        ReflectionTestUtils.setField(service, "maxParcelWeightGrams", 0);
        ReflectionTestUtils.setField(service, "maxParcelValueCents", 0);
        ReflectionTestUtils.setField(service, "maxParcelUnits", 0);
    }

    private void fila(String canal, String pais, int pesoMaximo) {
        CarrierChannelLimitEntity e = new CarrierChannelLimitEntity();
        e.setChannelCode(canal);
        e.setCountryCode(pais);
        e.setMaxWeightGrams(pesoMaximo);
        e.setActive(true);
        when(limitRepository.findByChannelCodeIgnoreCaseAndCountryCodeIgnoreCase(canal, pais))
                .thenReturn(Optional.of(e));
    }

    /** Cinco abrigos de 4 kg: 20 kg de una sola partida arancelaria. */
    private static List<Line> cincoAbrigos() {
        return List.of(new Line(UUID.randomUUID(), "620293", "Winter coat", "CN", 5, 3000, 4000, 0, 0, 0, false));
    }

    @Test
    @DisplayName("a Dinamarca el mismo carrito declara en dos bultos y a España en uno")
    void dinamarcaDeclaraEnDosBultosYEspanaEnUno() {
        List<DutyParcel> espana = service.parcelsOf(cincoAbrigos(), ROPA, "ES");
        List<DutyParcel> dinamarca = service.parcelsOf(cincoAbrigos(), ROPA, "DK");

        assertThat(espana).hasSize(1);
        assertThat(dinamarca).as("dos declaraciones, y por tanto dos veces el derecho de esa partida").hasSize(2);
    }

    @Test
    @DisplayName("sin canal conocido se conserva el comportamiento anterior: la configuración global")
    void sinCanalConocidoMandaLaConfiguracion() {
        // 0 = sin tope: todo en un bulto, que es lo que hacía antes de existir la tabla.
        assertThat(service.parcelsOf(cincoAbrigos())).hasSize(1);
    }
}
