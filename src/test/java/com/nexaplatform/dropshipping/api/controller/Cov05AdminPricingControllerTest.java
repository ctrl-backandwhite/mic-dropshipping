package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.exception.ArgumentException;
import com.nexaplatform.dropshipping.api.controller.AdminPricingController.BulkToggleRequest;
import com.nexaplatform.dropshipping.api.dto.in.PriceRuleDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.PriceRuleDtoOut;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.api.mapper.PriceRuleDtoMapper;
import com.nexaplatform.dropshipping.application.usecase.PriceRuleUseCase;
import com.nexaplatform.dropshipping.domain.model.PriceRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * API de reglas de precio del admin. Lo relevante aquí es el comportamiento de los lotes: un id
 * inválido no puede abortar la operación entera (el operador perdería el trabajo hecho sobre el resto)
 * y el nombre del alcance solo se consulta cuando hay reglas con alcance concreto.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov05AdminPricingControllerTest {

    @Mock
    PriceRuleDtoMapper mapper;
    @Mock
    PriceRuleUseCase useCase;
    @Mock
    JdbcTemplate jdbcTemplate;

    @InjectMocks
    AdminPricingController controller;

    private static final UUID CATEGORY_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    private static PriceRuleDtoOut rule(String scope, UUID scopeId) {
        return PriceRuleDtoOut.builder().id(UUID.randomUUID()).scope(scope).scopeId(scopeId).marginType("PERCENTAGE")
                .active(true).build();
    }

    /* ===================== CRUD ===================== */

    @Test
    @DisplayName("crear una regla responde 201 con la regla creada")
    void crearUnaReglaResponde201() {
        PriceRuleDtoIn req = new PriceRuleDtoIn();
        PriceRule model = new PriceRule();
        PriceRuleDtoOut out = rule("GLOBAL", null);
        when(mapper.toDomain(req)).thenReturn(model);
        when(useCase.save(model)).thenReturn(model);
        when(mapper.toDtoOut(model)).thenReturn(out);

        ResponseEntity<PriceRuleDtoOut> response = controller.create(req);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isSameAs(out);
    }

    @Test
    @DisplayName("actualizar una regla responde 200 y pasa el id al caso de uso")
    void actualizarUnaReglaResponde200() {
        UUID id = UUID.randomUUID();
        PriceRuleDtoIn req = new PriceRuleDtoIn();
        PriceRule model = new PriceRule();
        when(mapper.toDomain(req)).thenReturn(model);
        when(useCase.update(model, id)).thenReturn(model);
        when(mapper.toDtoOut(model)).thenReturn(rule("GLOBAL", null));

        ResponseEntity<PriceRuleDtoOut> response = controller.update(id, req);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(useCase).update(model, id);
    }

    @Test
    @DisplayName("borrar una regla responde 204 sin cuerpo")
    void borrarUnaReglaResponde204() {
        UUID id = UUID.randomUUID();

        ResponseEntity<Void> response = controller.delete(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
        verify(useCase).delete(id);
    }

    @Test
    @DisplayName("alternar una regla devuelve la regla actualizada")
    void alternarUnaReglaDevuelveLaReglaActualizada() {
        UUID id = UUID.randomUUID();
        PriceRule model = new PriceRule();
        PriceRuleDtoOut out = rule("GLOBAL", null);
        when(useCase.toggle(id)).thenReturn(model);
        when(mapper.toDtoOut(model)).thenReturn(out);

        ResponseEntity<PriceRuleDtoOut> response = controller.toggle(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(out);
    }

    /* ===================== nombre del alcance ===================== */

    @Test
    @DisplayName("si todas las reglas son globales no se consulta ningún nombre de alcance")
    void siTodasSonGlobalesNoSeConsultaNingunNombre() {
        when(useCase.findAll()).thenReturn(List.of());
        when(mapper.toDtoOutList(any())).thenReturn(List.of(rule("GLOBAL", null), rule("GLOBAL", null)));

        controller.list();

        // Una consulta por alcance en cada listado sería trabajo de base de datos gratuito.
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("una regla con alcance concreto resuelve el nombre legible de su entidad")
    void unaReglaConAlcanceConcretoResuelveSuNombre() throws Exception {
        PriceRuleDtoOut scoped = rule("CATEGORY", CATEGORY_ID);
        when(useCase.findAll()).thenReturn(List.of());
        when(mapper.toDtoOutList(any())).thenReturn(List.of(scoped));
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString(1)).thenReturn(CATEGORY_ID.toString());
        when(resultSet.getString(2)).thenReturn("Moda mujer");
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            handler.processRow(resultSet);
            return null;
        }).when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class), any());

        List<PriceRuleDtoOut> listed = controller.list().getBody();

        assertThat(listed).isNotNull();
        assertThat(listed.get(0).getScopeName()).isEqualTo("Moda mujer");
    }

    @Test
    @DisplayName("un alcance que ya no apunta a nada deja el nombre vacío en vez de romper el listado")
    void unAlcanceHuerfanoDejaElNombreVacio() {
        PriceRuleDtoOut scoped = rule("SUPPLIER", UUID.randomUUID());
        when(useCase.findAll()).thenReturn(List.of());
        when(mapper.toDtoOutList(any())).thenReturn(List.of(scoped));

        List<PriceRuleDtoOut> listed = controller.list().getBody();

        assertThat(listed).isNotNull();
        assertThat(listed.get(0).getScopeName()).isNull();
    }

    /* ===================== acciones en lote ===================== */

    @Test
    @DisplayName("un id que falla no aborta el lote: se cuentan los que sí salieron")
    void unIdQueFallaNoAbortaElLote() {
        UUID ok1 = UUID.randomUUID();
        UUID bad = UUID.randomUUID();
        UUID ok2 = UUID.randomUUID();
        doThrow(new NotFoundException("Regla")).when(useCase).delete(bad);

        Map<String, Object> body = controller.bulkDelete(List.of(ok1, bad, ok2)).getBody();

        assertThat(body).isNotNull();
        assertThat(body).containsEntry("succeeded", 2).containsEntry("failed", 1);
        // El operador tiene que poder ver QUÉ id falló y por qué, no solo un contador.
        @SuppressWarnings("unchecked")
        List<String> errors = (List<String>) body.get("errors");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).startsWith(bad.toString());
        verify(useCase).delete(ok1);
        verify(useCase).delete(ok2);
    }

    @Test
    @DisplayName("el error de cada id sale humanizado, nunca como excepción cruda")
    void elErrorDeCadaIdSaleHumanizado() {
        UUID bad = UUID.randomUUID();
        doThrow(new BusinessException("La regla está en uso")).when(useCase).delete(bad);

        Map<String, Object> body = controller.bulkDelete(List.of(bad)).getBody();

        assertThat(body).isNotNull();
        @SuppressWarnings("unchecked")
        List<String> errors = (List<String>) body.get("errors");
        assertThat(errors.get(0)).contains("La regla está en uso");
    }

    @Test
    @DisplayName("el lote de activación aplica el MISMO valor a todos los ids (no los alterna)")
    void elLoteDeActivacionAplicaElMismoValorATodos() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        Map<String, Object> body = controller.bulkToggle(new BulkToggleRequest(List.of(a, b), false)).getBody();

        assertThat(body).containsEntry("succeeded", 2).containsEntry("failed", 0);
        // Alternar en vez de fijar dejaría mitad activas y mitad no según su estado previo.
        verify(useCase).setActive(a, false);
        verify(useCase).setActive(b, false);
        verify(useCase, never()).toggle(any());
    }

    @Test
    @DisplayName("un lote vacío responde sin errores y sin tocar el caso de uso")
    void unLoteVacioSeRechazaComoPeticionInvalida() {
        // Un cuerpo sin ids es una petición mal formada, no un lote de cero: antes se recorría la lista
        // sin comprobar nulo y un bulk-delete con el cuerpo vacío salía como 500 sin explicación.
        BulkToggleRequest sinIds = new BulkToggleRequest(List.of(), true);

        assertThatThrownBy(() -> controller.bulkToggle(sinIds)).isInstanceOf(ArgumentException.class);

        verify(useCase, never()).setActive(any(), anyBoolean());
    }

    @Test
    @DisplayName("el lote de activación puede activar tantas reglas como desactivarlas")
    void elLoteDeActivacionTambienActiva() {
        UUID a = UUID.randomUUID();

        controller.bulkToggle(new BulkToggleRequest(List.of(a), true));

        verify(useCase).setActive(eq(a), eq(true));
    }
}
