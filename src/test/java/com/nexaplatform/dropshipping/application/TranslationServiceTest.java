package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.TranslationService;
import com.nexaplatform.dropshipping.infrastructure.integration.translation.TranslationProvider;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TranslationServiceTest {

    @Mock
    TranslationProvider provider;
    @Mock
    StringRedisTemplate redis;
    @Mock
    ValueOperations<String, String> valueOps;
    @Mock
    ProductRepository productRepository;
    @InjectMocks
    TranslationService service;

    @BeforeEach
    void initConfig() {
        ReflectionTestUtils.setField(service, "defaultSource", "zh");
        ReflectionTestUtils.setField(service, "targetLanguagesRaw", "es, en");
    }

    private static ProductEntity product(UUID id, String titleZh) {
        ProductEntity p = ProductEntity.builder().titleZh(titleZh).build();
        p.setId(id);
        return p;
    }

    @Test
    void translateProduct_noOpWhenProductMissing() {
        UUID id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.empty());

        service.translateProduct(id);

        verify(productRepository, never()).save(any());
        verifyNoInteractions(provider, redis);
    }

    @Test
    void translateProduct_blankFieldsSkipTranslationButStillUpsert() {
        UUID id = UUID.randomUUID();
        // titleZh nulo y short/desc nulos -> translateCached devuelve el texto sin traducir ni tocar redis.
        ProductEntity p = product(id, null);
        when(productRepository.findById(id)).thenReturn(Optional.of(p));
        when(provider.name()).thenReturn("dummy");

        service.translateProduct(id);

        // No se traduce ni se consulta la caché para texto en blanco, pero sí se crea la fila de traducción.
        verify(provider, never()).translate(anyString(), anyString(), anyString());
        verifyNoInteractions(redis);
        verify(productRepository).save(p);
        assertThat(p.getTranslations()).extracting(ProductTranslationEntity::getLanguage)
                .containsExactlyInAnyOrder("es", "en");
    }

    @Test
    void translateProduct_usesCacheHitWhenPresent() {
        UUID id = UUID.randomUUID();
        ProductEntity p = product(id, "你好");
        when(productRepository.findById(id)).thenReturn(Optional.of(p));
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn("cached");
        when(provider.name()).thenReturn("dummy");

        service.translateProduct(id);

        // En cache hit no se invoca al proveedor de traducción ni se reescribe la caché.
        verify(provider, never()).translate(anyString(), anyString(), anyString());
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
        ProductTranslationEntity es = p.getTranslations().stream()
                .filter(t -> "es".equals(t.getLanguage())).findFirst().orElseThrow();
        assertThat(es.getTitle()).isEqualTo("cached");
        assertThat(es.getProvider()).isEqualTo("dummy");
    }

    @Test
    void translateProduct_callsProviderOnCacheMissAndStoresResult() {
        UUID id = UUID.randomUUID();
        ProductEntity p = product(id, "你好");
        when(productRepository.findById(id)).thenReturn(Optional.of(p));
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        when(provider.name()).thenReturn("dummy");
        when(provider.translate(eq("你好"), eq("zh"), anyString())).thenReturn("hello");

        service.translateProduct(id);

        // Dos idiomas (es, en) -> el proveedor se usa una vez por idioma, y cada resultado se cachea 90 días.
        verify(provider).translate("你好", "zh", "es");
        verify(provider).translate("你好", "zh", "en");
        verify(valueOps, org.mockito.Mockito.times(2)).set(anyString(), eq("hello"), eq(Duration.ofDays(90)));
        ProductTranslationEntity es = p.getTranslations().stream()
                .filter(t -> "es".equals(t.getLanguage())).findFirst().orElseThrow();
        assertThat(es.getTitle()).isEqualTo("hello");
    }

    @Test
    void translateProduct_swallowsProviderErrorsPerLanguageAndStillSaves() {
        UUID id = UUID.randomUUID();
        ProductEntity p = product(id, "你好");
        when(productRepository.findById(id)).thenReturn(Optional.of(p));
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        lenient().when(provider.name()).thenReturn("dummy");
        when(provider.translate(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("boom"));

        service.translateProduct(id);

        // La excepción por idioma se captura: el flujo termina y persiste el producto igualmente.
        verify(productRepository).save(p);
    }
}
