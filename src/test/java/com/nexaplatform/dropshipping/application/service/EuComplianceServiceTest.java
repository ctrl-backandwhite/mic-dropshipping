package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.application.service.EuComplianceService.ProductComplianceView;
import com.nexaplatform.dropshipping.application.service.EuComplianceService.ResponsiblePersonView;
import com.nexaplatform.dropshipping.application.service.EuComplianceService.SafetyWarningView;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategorySafetyWarningEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategorySafetyWarningTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.EuResponsiblePersonEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategorySafetyWarningRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.EuResponsiblePersonRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reglas del operador económico de la UE y de las advertencias de seguridad.
 *
 * <p>Lo que se fija aquí es sobre todo QUÉ CUENTA COMO PUBLICABLE: el art. 16.3 del Reglamento (UE)
 * 2023/988 exige dirección postal y correo, y una dirección sin código postal no permite contactar. Si esa
 * comprobación se relajara, el escaparate publicaría un bloque que aparenta cumplir sin cumplir — que es
 * peor que no publicarlo, porque nadie lo detectaría.
 */
@ExtendWith(MockitoExtension.class)
class EuComplianceServiceTest {

    @Mock
    private EuResponsiblePersonRepository responsibleRepository;

    @Mock
    private CategorySafetyWarningRepository warningRepository;

    @Mock
    private EuComplianceLookup lookup;

    @InjectMocks
    private EuComplianceService service;

    private EuResponsiblePersonEntity completo;

    @BeforeEach
    void setUp() {
        completo = new EuResponsiblePersonEntity();
        completo.setId((short) 1);
        completo.setEnabled(true);
        completo.setName("Jesus Enrique Finol Finol");
        completo.setAddressLine("Calle Catelvi 7, 1D");
        completo.setPostalCode("50001");
        completo.setCity("Zaragoza");
        completo.setCountry("ES");
        completo.setEmail("jfinol02@gmail.com");
        completo.setRole("IMPORTER");
    }

    @Test
    @DisplayName("con todos los datos del art. 16.3, el operador es completo y se publica")
    void publicaCuandoEstaCompleto() {
        when(lookup.responsible("es")).thenReturn(Optional.of(EuComplianceLookup.toView(completo, "es")));

        Optional<ResponsiblePersonView> publicado = service.publishedResponsible("es");

        assertThat(publicado).isPresent();
        assertThat(publicado.get().complete()).isTrue();
        assertThat(publicado.get().roleLabel()).isEqualTo("Importador");
    }

    @Test
    @DisplayName("sin código postal NO se publica: una dirección incompleta no cumple el art. 16.3")
    void noPublicaSinCodigoPostal() {
        completo.setPostalCode(null);
        when(lookup.responsible("es")).thenReturn(Optional.of(EuComplianceLookup.toView(completo, "es")));

        // Sigue siendo visible para el panel —su trabajo es enseñar lo que falta—, pero no para el público.
        assertThat(service.responsible("es")).isPresent();
        assertThat(service.responsible("es").get().complete()).isFalse();
        assertThat(service.publishedResponsible("es")).isEmpty();
    }

    @Test
    @DisplayName("deshabilitado no se publica aunque esté completo")
    void noPublicaSiEstaDeshabilitado() {
        completo.setEnabled(false);
        when(lookup.responsible("es")).thenReturn(Optional.of(EuComplianceLookup.toView(completo, "es")));

        assertThat(service.responsible("es").get().complete()).isTrue();
        assertThat(service.publishedResponsible("es")).isEmpty();
    }

    @Test
    @DisplayName("sin fila configurada devuelve vacío en vez de reventar")
    void sinFilaNoRompe() {
        when(lookup.responsible("es")).thenReturn(Optional.empty());

        assertThat(service.responsible("es")).isEmpty();
        assertThat(service.publishedResponsible("es")).isEmpty();
    }

    @Test
    @DisplayName("la dirección de una línea junta calle, código postal, ciudad y país")
    void formateaLaDireccion() {
        ResponsiblePersonView v = EuComplianceLookup.toView(completo, "es");

        assertThat(v.formattedAddress()).isEqualTo("Calle Catelvi 7, 1D, 50001 Zaragoza (ES)");
    }

    @Test
    @DisplayName("sin código postal la dirección no deja una coma suelta")
    void formateaSinCodigoPostal() {
        completo.setPostalCode(null);

        assertThat(EuComplianceLookup.toView(completo, "es").formattedAddress())
                .isEqualTo("Calle Catelvi 7, 1D, Zaragoza (ES)");
    }

    @ParameterizedTest(name = "idioma {0} → figura \"{1}\"")
    @CsvSource({"es,Importador", "en,Importer", "pt,Importador", "fr,Importateur", "de,Importeur",
            "it,Importatore", "nl,Importeur"})
    @DisplayName("la figura del art. 4.2 viaja traducida: el front no la traduce")
    void traduceLaFigura(String lang, String esperado) {
        assertThat(EuComplianceLookup.toView(completo, lang).roleLabel()).isEqualTo(esperado);
    }

    @Test
    @DisplayName("un idioma no soportado cae a español en vez de romper el índice")
    void idiomaDesconocidoCaeAEspanol() {
        assertThat(EuComplianceLookup.toView(completo, "ja").roleLabel()).isEqualTo("Importador");
        assertThat(EuComplianceLookup.toView(completo, null).roleLabel()).isEqualTo("Importador");
    }

    @Test
    @DisplayName("el idioma se normaliza antes de consultar: 'ES' y ' es ' son el mismo")
    void normalizaElIdioma() {
        when(lookup.responsible("es")).thenReturn(Optional.of(EuComplianceLookup.toView(completo, "es")));

        assertThat(service.responsible("ES")).isPresent();
        assertThat(service.responsible("  es  ")).isPresent();
        assertThat(service.responsible(null)).isPresent();
        assertThat(service.responsible("")).isPresent();
    }

    @Test
    @DisplayName("el fabricante solo está completo con nombre, dirección y correo (art. 19.a)")
    void fabricanteCompletoExigeLosTres() {
        UUID cat = UUID.randomUUID();
        when(lookup.safetyWarnings(eq(cat), anyString())).thenReturn(List.of());
        when(lookup.responsible(anyString())).thenReturn(Optional.empty());

        assertThat(service.forProduct(cat, "Fábrica S.L.", "Calle 1", "a@b.com", "es").manufacturerComplete())
                .isTrue();
        assertThat(service.forProduct(cat, "Fábrica S.L.", "Calle 1", null, "es").manufacturerComplete())
                .isFalse();
        assertThat(service.forProduct(cat, "Fábrica S.L.", "  ", "a@b.com", "es").manufacturerComplete())
                .isFalse();
        assertThat(service.forProduct(cat, null, "Calle 1", "a@b.com", "es").manufacturerComplete())
                .isFalse();
    }

    @Test
    @DisplayName("sin categoría no se consultan advertencias ni se rompe la ficha")
    void categoriaNulaDevuelveListaVacia() {
        when(lookup.safetyWarnings(null, "es")).thenReturn(List.of());
        when(lookup.responsible("es")).thenReturn(Optional.empty());

        ProductComplianceView v = service.forProduct(null, null, null, null, "es");

        assertThat(v.safetyWarnings()).isEmpty();
        assertThat(v.responsiblePerson()).isNull();
        assertThat(v.manufacturerComplete()).isFalse();
    }

    @Test
    @DisplayName("al guardar, el correo se pasa a minúsculas y el país a mayúsculas")
    void normalizaAlGuardar() {
        when(responsibleRepository.findById((short) 1)).thenReturn(Optional.empty());
        when(responsibleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updateResponsible(new ResponsiblePersonView("  Nombre  ", " Calle 1 ", " 50001 ", "Zaragoza",
                null, "es", "  MAYUS@Ejemplo.COM ", null, "importer", null, true, false), "admin@x", "es");

        ArgumentCaptor<EuResponsiblePersonEntity> captor =
                ArgumentCaptor.forClass(EuResponsiblePersonEntity.class);
        verify(responsibleRepository).save(captor.capture());
        EuResponsiblePersonEntity guardado = captor.getValue();
        assertThat(guardado.getEmail()).isEqualTo("mayus@ejemplo.com");
        assertThat(guardado.getCountry()).isEqualTo("ES");
        assertThat(guardado.getName()).isEqualTo("Nombre");
        assertThat(guardado.getPostalCode()).isEqualTo("50001");
        assertThat(guardado.getRole()).isEqualTo("IMPORTER");
        assertThat(guardado.getUpdatedBy()).isEqualTo("admin@x");
    }

    @Test
    @DisplayName("una figura desconocida cae a IMPORTER en vez de guardar basura")
    void figuraDesconocidaCaeAImporter() {
        when(responsibleRepository.findById((short) 1)).thenReturn(Optional.empty());
        when(responsibleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updateResponsible(new ResponsiblePersonView("N", "C", "50001", "Z", null, "ES", "a@b.com",
                null, "NO_EXISTE", null, true, false), "admin@x", "es");

        ArgumentCaptor<EuResponsiblePersonEntity> captor =
                ArgumentCaptor.forClass(EuResponsiblePersonEntity.class);
        verify(responsibleRepository).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo("IMPORTER");
    }

    @Test
    @DisplayName("los campos opcionales vacíos se guardan como null, no como cadena vacía")
    void opcionalesVaciosVanANull() {
        when(responsibleRepository.findById((short) 1)).thenReturn(Optional.empty());
        when(responsibleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updateResponsible(new ResponsiblePersonView("N", "C", "  ", "Z", "   ", "ES", "a@b.com",
                "", "IMPORTER", null, false, false), "admin@x", "es");

        ArgumentCaptor<EuResponsiblePersonEntity> captor =
                ArgumentCaptor.forClass(EuResponsiblePersonEntity.class);
        verify(responsibleRepository).save(captor.capture());
        // Importa porque la comprobación de completitud mira "en blanco": un "" guardado haría que el
        // registro pareciera relleno y se publicara un bloque sin código postal.
        assertThat(captor.getValue().getPostalCode()).isNull();
        assertThat(captor.getValue().getRegion()).isNull();
        assertThat(captor.getValue().getPhone()).isNull();
    }

    @Test
    @DisplayName("upsert de advertencia: crea si no existe y normaliza el código a mayúsculas")
    void upsertCreaAdvertencia() {
        UUID cat = UUID.randomUUID();
        when(warningRepository.findByCategoryIdOrderByPositionAsc(cat)).thenReturn(List.of());
        when(warningRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SafetyWarningView v = service.upsertWarning(cat, " button_battery ", 10, true,
                Map.of("es", " Contiene pila de botón ", "en", "Contains a button battery"), "admin@x");

        assertThat(v.code()).isEqualTo("BUTTON_BATTERY");
        assertThat(v.position()).isEqualTo(10);
        assertThat(v.active()).isTrue();
        assertThat(v.texts()).containsEntry("es", "Contiene pila de botón");
        assertThat(v.texts()).containsEntry("en", "Contains a button battery");
    }

    @Test
    @DisplayName("upsert sobre una existente conserva los idiomas que no se mandan")
    void upsertConservaTraduccionesNoEnviadas() {
        UUID cat = UUID.randomUUID();
        CategorySafetyWarningEntity existente = new CategorySafetyWarningEntity();
        existente.setId(UUID.randomUUID());
        existente.setCategoryId(cat);
        existente.setCode("BUTTON_BATTERY");
        existente.setActive(true);
        existente.getTranslations().add(traduccion(existente.getId(), "es", "Texto ES"));
        existente.getTranslations().add(traduccion(existente.getId(), "fr", "Texte FR"));
        when(warningRepository.findByCategoryIdOrderByPositionAsc(cat)).thenReturn(List.of(existente));
        when(warningRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Solo se manda inglés: español y francés tienen que seguir ahí. Traducir un idioma no puede
        // dejar sin advertencia a los compradores de los otros.
        SafetyWarningView v = service.upsertWarning(cat, "BUTTON_BATTERY", null, null,
                Map.of("en", "Contains a button battery"), "admin@x");

        assertThat(v.texts()).containsKeys("es", "fr", "en");
        assertThat(v.texts()).containsEntry("es", "Texto ES");
    }

    @Test
    @DisplayName("los textos en blanco se ignoran: no se guarda una advertencia vacía")
    void ignoraTextosEnBlanco() {
        UUID cat = UUID.randomUUID();
        when(warningRepository.findByCategoryIdOrderByPositionAsc(cat)).thenReturn(List.of());
        when(warningRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, String> textos = new java.util.HashMap<>();
        textos.put("es", "Texto válido");
        textos.put("en", "   ");
        textos.put("fr", null);
        SafetyWarningView v = service.upsertWarning(cat, "X_CODE", 0, true, textos, "admin@x");

        assertThat(v.texts()).containsOnlyKeys("es");
    }

    private static CategorySafetyWarningTranslationEntity traduccion(UUID warningId, String lang, String texto) {
        CategorySafetyWarningTranslationEntity t = new CategorySafetyWarningTranslationEntity();
        t.setId(UUID.randomUUID());
        t.setWarningId(warningId);
        t.setLanguage(lang);
        t.setText(texto);
        return t;
    }

    @Test
    @DisplayName("borrar una advertencia delega en el repositorio")
    void borraAdvertencia() {
        UUID id = UUID.randomUUID();

        service.deleteWarning(id);

        verify(warningRepository).deleteById(id);
        verify(warningRepository, never()).save(any());
    }
}
