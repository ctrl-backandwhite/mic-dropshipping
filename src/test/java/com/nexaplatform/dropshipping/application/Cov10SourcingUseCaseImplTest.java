package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.usecase.impl.SourcingUseCaseImpl;
import com.nexaplatform.dropshipping.domain.model.SourcingAgent;
import com.nexaplatform.dropshipping.domain.model.SourcingQuote;
import com.nexaplatform.dropshipping.domain.model.SourcingRequest;
import com.nexaplatform.dropshipping.domain.repository.SourcingAgentRepository;
import com.nexaplatform.dropshipping.domain.repository.SourcingQuoteRepository;
import com.nexaplatform.dropshipping.domain.repository.SourcingRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reglas del sourcing que la suite existente no cubre: validación del enlace de origen, propiedad de la
 * solicitud (nadie ve ni toca la de otro) y el ciclo de las ofertas de los agentes.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov10SourcingUseCaseImplTest {

    @Mock
    SourcingRequestRepository sourcingRequestRepository;
    @Mock
    SourcingQuoteRepository sourcingQuoteRepository;
    @Mock
    SourcingAgentRepository sourcingAgentRepository;

    @InjectMocks
    SourcingUseCaseImpl useCase;

    /* ---------- alta de solicitudes ---------- */

    @Test
    void unTextoLibreNoEsUnEnlaceDeProductoYSeRechaza() {
        UUID userId = UUID.randomUUID();
        when(sourcingRequestRepository.countByUserIdAndCreatedAtAfter(eq(userId), any(Instant.class))).thenReturn(0L);

        // El formulario aceptaba cualquier texto: el agente recibía solicitudes que no podía trabajar.
        assertThatThrownBy(() -> useCase.create(userId, "quiero unas zapatillas", null, null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> useCase.create(userId, null, null, null)).isInstanceOf(BusinessException.class);
        verify(sourcingRequestRepository, never()).save(any());
    }

    @Test
    void unMarketplaceDesconocidoSeRechazaAunqueLaUrlSeaValida() {
        UUID userId = UUID.randomUUID();
        when(sourcingRequestRepository.countByUserIdAndCreatedAtAfter(eq(userId), any(Instant.class))).thenReturn(0L);

        assertThatThrownBy(() -> useCase.create(userId, "https://www.temu.com/x", null, null))
                .isInstanceOf(BusinessException.class);
        verify(sourcingRequestRepository, never()).save(any());
    }

    @Test
    void cadaMarketplaceSoportadoSeReconocePorSuDominio() {
        UUID userId = UUID.randomUUID();
        when(sourcingRequestRepository.countByUserIdAndCreatedAtAfter(eq(userId), any(Instant.class))).thenReturn(0L);
        when(sourcingRequestRepository.save(any(SourcingRequest.class))).thenAnswer(inv -> inv.getArgument(0));
        when(sourcingQuoteRepository.findByRequestIdOrderByPriceUsdCentsAsc(any())).thenReturn(List.of());

        assertThat(useCase.create(userId, "HTTPS://Detail.1688.com/offer/1.html", null, null).getSource())
                .isEqualTo("1688");
        assertThat(useCase.create(userId, "https://item.taobao.com/x", null, null).getSource()).isEqualTo("taobao");
        assertThat(useCase.create(userId, "https://es.aliexpress.com/x", null, null).getSource())
                .isEqualTo("aliexpress");
        assertThat(useCase.create(userId, "http://www.ebay.com/x", null, null).getSource()).isEqualTo("ebay");
        assertThat(useCase.create(userId, "https://amazon.es/dp/x", null, null).getSource()).isEqualTo("amazon");
    }

    @Test
    void elEnlaceSeGuardaSinEspaciosSobrantes() {
        UUID userId = UUID.randomUUID();
        when(sourcingRequestRepository.countByUserIdAndCreatedAtAfter(eq(userId), any(Instant.class))).thenReturn(0L);
        when(sourcingRequestRepository.save(any(SourcingRequest.class))).thenAnswer(inv -> inv.getArgument(0));
        when(sourcingQuoteRepository.findByRequestIdOrderByPriceUsdCentsAsc(any())).thenReturn(List.of());

        SourcingRequest r = useCase.create(userId, "  https://detail.1688.com/offer/1.html  ", "camisa", "urgente");

        assertThat(r.getSourceUrl()).isEqualTo("https://detail.1688.com/offer/1.html");
        assertThat(r.getPlanQuota()).isEqualTo("FREE");
        assertThat(r.getTitleHint()).isEqualTo("camisa");
        assertThat(r.getNotes()).isEqualTo("urgente");
    }

    /* ---------- propiedad ---------- */

    @Test
    void elListadoDelUsuarioTraeElNumeroDeOfertasDeCadaSolicitud() {
        UUID userId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(sourcingRequestRepository.findByUserIdOrderByCreatedAtDesc(userId))
                .thenReturn(List.of(SourcingRequest.builder().id(id).userId(userId).build()));
        when(sourcingQuoteRepository.findByRequestIdOrderByPriceUsdCentsAsc(id))
                .thenReturn(List.of(SourcingQuote.builder().build(), SourcingQuote.builder().build()));

        assertThat(useCase.myRequests(userId)).singleElement().extracting(SourcingRequest::getQuotesCount)
                .isEqualTo(2L);
    }

    @Test
    void unaSolicitudSinDuenoNoEsAccesibleParaNadie() {
        UUID userId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(sourcingRequestRepository.getById(id)).thenReturn(SourcingRequest.builder().id(id).userId(null).build());

        assertThatThrownBy(() -> useCase.detail(userId, id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void unaSolicitudInexistenteDaNoEncontrada() {
        UUID userId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(sourcingRequestRepository.getById(id)).thenReturn(null);

        assertThatThrownBy(() -> useCase.detail(userId, id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void lasOfertasDeOtroUsuarioNoSePuedenLeer() {
        UUID intruso = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(sourcingRequestRepository.getById(id))
                .thenReturn(SourcingRequest.builder().id(id).userId(UUID.randomUUID()).build());

        assertThatThrownBy(() -> useCase.quotes(intruso, id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void cancelarNoBorraLaSolicitudSoloLaMarca() {
        UUID userId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        SourcingRequest r = SourcingRequest.builder().id(id).userId(userId).status("PENDING").build();
        when(sourcingRequestRepository.getById(id)).thenReturn(r);
        when(sourcingRequestRepository.save(r)).thenReturn(r);
        when(sourcingQuoteRepository.findByRequestIdOrderByPriceUsdCentsAsc(id)).thenReturn(List.of());

        assertThat(useCase.cancel(userId, id).getStatus()).isEqualTo("CANCELLED");
        verify(sourcingRequestRepository, never()).delete(any());
    }

    @Test
    void unaSolicitudSinOfertaAceptadaSePuedeBorrar() {
        UUID userId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(sourcingRequestRepository.getById(id)).thenReturn(SourcingRequest.builder().id(id).userId(userId).build());
        when(sourcingQuoteRepository.findByRequestIdOrderByPriceUsdCentsAsc(id))
                .thenReturn(List.of(SourcingQuote.builder().status("OPEN").build()));

        useCase.delete(userId, id);

        verify(sourcingRequestRepository).delete(id);
    }

    /* ---------- ofertas ---------- */

    @Test
    void noSePuedeOfertarSobreUnaSolicitudQueNoExiste() {
        UUID id = UUID.randomUUID();
        when(sourcingRequestRepository.getById(id)).thenReturn(null);

        assertThatThrownBy(() -> useCase.submitQuote(id, 1000, 7, 10, null, null))
                .isInstanceOf(NotFoundException.class);
        verify(sourcingQuoteRepository, never()).save(any());
    }

    @Test
    void noSePuedeOfertarEnNombreDeUnAgenteQueNoExiste() {
        UUID id = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        when(sourcingRequestRepository.getById(id)).thenReturn(SourcingRequest.builder().id(id).build());
        when(sourcingAgentRepository.getById(agentId)).thenReturn(null);

        assertThatThrownBy(() -> useCase.submitQuote(id, 1000, 7, null, null, agentId))
                .isInstanceOf(NotFoundException.class);
        verify(sourcingQuoteRepository, never()).save(any());
    }

    @Test
    void laPrimeraOfertaPasaLaSolicitudAEnCotizacion() {
        UUID id = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        SourcingRequest r = SourcingRequest.builder().id(id).status("PENDING").build();
        SourcingAgent agent = SourcingAgent.builder().id(agentId).active(true).build();
        when(sourcingRequestRepository.getById(id)).thenReturn(r);
        when(sourcingAgentRepository.getById(agentId)).thenReturn(agent);
        when(sourcingQuoteRepository.save(any(SourcingQuote.class))).thenAnswer(inv -> inv.getArgument(0));

        SourcingQuote quote = useCase.submitQuote(id, 12_500, 9, 30, "sin IVA", agentId);

        assertThat(quote.getRequestId()).isEqualTo(id);
        assertThat(quote.getAgent()).isSameAs(agent);
        assertThat(quote.getPriceUsdCents()).isEqualTo(12_500);
        assertThat(quote.getEtaDays()).isEqualTo(9);
        assertThat(quote.getMoq()).isEqualTo(30);
        assertThat(quote.getStatus()).isEqualTo("OPEN");
        assertThat(r.getStatus()).isEqualTo("QUOTING");
        verify(sourcingRequestRepository).save(r);
    }

    @Test
    void unaOfertaPosteriorNoReescribeElEstadoDeLaSolicitud() {
        UUID id = UUID.randomUUID();
        SourcingRequest r = SourcingRequest.builder().id(id).status("QUOTING").build();
        when(sourcingRequestRepository.getById(id)).thenReturn(r);
        when(sourcingQuoteRepository.save(any(SourcingQuote.class))).thenAnswer(inv -> inv.getArgument(0));

        useCase.submitQuote(id, 9_900, 5, null, null, null);

        // Reescribirla en cada oferta pisaría un APPROVED y devolvería la solicitud a cotización.
        verify(sourcingRequestRepository, never()).save(any());
    }

    @Test
    void noSePuedeElegirUnaOfertaQueNoExiste() {
        UUID userId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        when(sourcingRequestRepository.getById(id)).thenReturn(SourcingRequest.builder().id(id).userId(userId).build());
        when(sourcingQuoteRepository.getById(quoteId)).thenReturn(null);

        assertThatThrownBy(() -> useCase.selectQuote(userId, id, quoteId)).isInstanceOf(NotFoundException.class);
    }

    /* ---------- agentes ---------- */

    @Test
    void elMercadoDeAgentesSoloMuestraLosActivos() {
        SourcingAgent activo = SourcingAgent.builder().id(UUID.randomUUID()).active(true).build();
        when(sourcingAgentRepository.findByActiveTrueOrderBySatisfactionDesc()).thenReturn(List.of(activo));

        assertThat(useCase.agentsList()).containsExactly(activo);
    }

    @Test
    void laFichaDeUnAgenteInexistenteDaNoEncontrado() {
        UUID id = UUID.randomUUID();
        when(sourcingAgentRepository.getById(id)).thenReturn(null);

        assertThatThrownBy(() -> useCase.agentDetail(id)).isInstanceOf(NotFoundException.class);
    }
}
