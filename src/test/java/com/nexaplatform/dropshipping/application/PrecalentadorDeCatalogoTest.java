package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.StorefrontCatalogApi;
import com.nexaplatform.dropshipping.api.mapper.CatalogStorefrontReadService;
import com.nexaplatform.dropshipping.application.service.PrecalentadorDeCatalogo;
import com.nexaplatform.dropshipping.application.service.PricingChannelHolder;
import com.nexaplatform.dropshipping.application.service.PricingCountryHolder;
import com.nexaplatform.dropshipping.domain.enums.PriceRuleChannel;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.KeyGenerator;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El precalentado del escaparate.
 *
 * <p>Por qué existe: la caché funciona —la portada tarda 3-6 segundos la primera vez y 0,03 la
 * segunda—, pero casi nadie llegaba a ser la segunda. La caché vive dentro de cada réplica, caduca a
 * los cinco minutos y se parte por idioma, divisa, canal, país y rol, así que a horas tranquilas casi
 * todo el mundo pagaba la primera. Medido en PRE el 5-sep-2026: 5,05 / 1,82 / 2,04 / 1,83 s
 * seguidos, sin bajar nunca a los tiempos de caché caliente.
 *
 * <p>Lo que se fija aquí es lo que hace falta para que el precalentado ayude en vez de estorbar.
 */
@DisplayName("Precalentado del escaparate")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PrecalentadorDeCatalogoTest {

    @Mock
    StorefrontCatalogApi catalogo;
    @Mock
    CatalogStorefrontReadService lectura;
    @Mock
    CacheManager cacheManager;
    @Mock
    Cache cache;
    @Mock
    KeyGenerator currencyAwareKeyGenerator;

    @InjectMocks
    PrecalentadorDeCatalogo precalentador;

    @BeforeEach
    void montar() throws Exception {
        set("activo", true);
        set("porSeccion", 6);
        set("combinaciones", List.of("es:EUR", "en:USD"));
        when(cacheManager.getCache(any())).thenReturn(cache);
        // generate(target, metodo, Object... params): la portada pasa DOS parámetros, así que hacen
        // falta cuatro comparadores. Con tres, el stub no casaba y la clave salía nula — que es
        // exactamente lo que este test destapó.
        when(currencyAwareKeyGenerator.generate(any(), any(), any(), any())).thenReturn("clave");
        CurrencyHolder.clear();
        PricingChannelHolder.clear();
        PricingCountryHolder.clear();
    }

    @Test
    @DisplayName("calienta cada combinación configurada")
    void calienta_cada_combinacion() {
        precalentador.precalienta();

        verify(catalogo).homeSections("es", 6);
        verify(catalogo).homeSections("en", 6);
        verify(lectura).categoriesTree("es");
        verify(lectura).categoriesTree("en");
        verify(lectura, times(2)).productListFull(eq(0), eq(24), any(), any(), any());
    }

    @Test
    @DisplayName("no deja la divisa pegada al hilo: si no, la siguiente petición que lo reutilice tarifaría en la divisa equivocada")
    void no_deja_rastro_en_el_hilo() {
        precalentador.precalienta();

        // La última combinación calentada fue "en:USD". Si el precalentado no limpiara, el hilo se
        // quedaría con esa divisa pegada; al reutilizarlo Spring para atender a alguien, esa persona
        // vería los precios en la divisa del precalentado y no en la suya.
        assertThat(CurrencyHolder.get())
                .as("tras limpiar, el hilo vuelve al valor por defecto, no al de la última combinación")
                .isEqualTo("USD");
        assertThat(PricingChannelHolder.get())
                .as("el canal vuelve al del escaparate, no al que dejó la última combinación")
                .isEqualTo(PriceRuleChannel.STOREFRONT);
        assertThat(PricingCountryHolder.get()).isNull();
    }

    @Test
    @DisplayName("si una combinación falla, las demás se calientan igual")
    void un_fallo_no_arrastra_a_las_demas() {
        when(catalogo.homeSections(eq("es"), anyInt())).thenThrow(new IllegalStateException("base ocupada"));

        precalentador.precalienta();

        // La que falla no tumba la tarea, y la siguiente se calienta.
        verify(catalogo).homeSections("en", 6);
    }

    @Test
    @DisplayName("apagado no hace nada: tiene que poder desactivarse sin desplegar código")
    void apagado_no_toca_nada() throws Exception {
        set("activo", false);

        precalentador.precalienta();

        verify(catalogo, never()).homeSections(any(), anyInt());
        verify(lectura, never()).categoriesTree(any());
    }

    @Test
    @DisplayName("una combinación mal escrita se ignora sin romper el resto")
    void combinacion_mal_escrita_se_ignora() throws Exception {
        set("combinaciones", List.of("esto-esta-mal", "en:USD"));

        precalentador.precalienta();

        verify(catalogo).homeSections("en", 6);
        verify(catalogo, never()).homeSections("esto-esta-mal", 6);
    }

    @Test
    @DisplayName("desaloja la entrada ANTES de pedirla: si no, el precalentado deja de calentar tras la primera vez")
    void desaloja_antes_de_pedir() {
        precalentador.precalienta();

        // Es EL fallo que tuvo la primera version. @Cacheable devuelve lo guardado sin reescribirlo,
        // asi que la caducidad seguia contando desde la primera escritura: medido en PRE, el segundo
        // ciclo tardaba 2 ms (acierto de cache, no calentaba) y quedaban TRES minutos frios de cada
        // ocho. Sin este desalojo el precalentado parece funcionar y no funciona.
        InOrder orden = inOrder(cache, catalogo);
        orden.verify(cache).evict(any());
        orden.verify(catalogo).homeSections("es", 6);
    }

    @Test
    @DisplayName("la clave la pide al MISMO generador que usa la cache, no la compone a mano")
    void usa_el_generador_de_claves_real() {
        precalentador.precalienta();

        // Componer aqui una copia del formato del generador dejaria el precalentado desalojando una
        // clave inexistente en cuanto alguien tocara el generador, y sin que nada fallara.
        verify(currencyAwareKeyGenerator, atLeastOnce()).generate(any(), any(), eq("es"), eq(6));
    }

    private void set(String campo, Object valor) throws Exception {
        Field f = PrecalentadorDeCatalogo.class.getDeclaredField(campo);
        f.setAccessible(true);
        f.set(precalentador, valor);
    }
}
