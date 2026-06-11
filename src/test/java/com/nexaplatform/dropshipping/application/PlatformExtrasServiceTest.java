package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.dto.in.OdmStatusUpdateDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.ShippingCalculatorDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.SupportTicketResolveDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.CarbonFootprintDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.OdmProjectDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.ShippingRateDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.SupportTicketDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.UnreadCountDtoOut;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.api.mapper.PlatformExtrasMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OdmProjectEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupportTicketEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.NotificationRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OdmProjectRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PodDesignRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductWarehouseStockRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SupportTicketRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.WarehouseRepository;
import com.nexaplatform.dropshipping.application.service.PlatformExtrasService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlatformExtrasServiceTest {

    @Mock PodDesignRepository podRepo;
    @Mock OdmProjectRepository odmRepo;
    @Mock SupportTicketRepository ticketRepo;
    @Mock NotificationRepository notifRepo;
    @Mock WarehouseRepository warehouseRepo;
    @Mock ProductWarehouseStockRepository stockRepo;
    @Mock UserRepository userRepo;
    @Mock ProductRepository productRepo;
    @Mock OrderRepository orderRepo;
    @Mock PlatformExtrasMapper mapper;

    private PlatformExtrasService service() {
        return new PlatformExtrasService(podRepo, odmRepo, ticketRepo, notifRepo, warehouseRepo,
                stockRepo, userRepo, productRepo, orderRepo, mapper);
    }

    @Test
    void unreadCount_wrapsRepositoryCounter() {
        UUID userId = UUID.randomUUID();
        when(notifRepo.countByUser_IdAndReadAtIsNull(userId)).thenReturn(7L);

        UnreadCountDtoOut result = service().unreadCount(userId);

        assertThat(result.getCount()).isEqualTo(7L);
    }

    @Test
    void setOdmStatus_updatesAndDelegatesToMapper() {
        UUID id = UUID.randomUUID();
        var project = OdmProjectEntity.builder().status("INTAKE").build();
        when(odmRepo.findById(id)).thenReturn(Optional.of(project));
        when(odmRepo.save(project)).thenReturn(project);
        when(mapper.toOdmDto(project)).thenReturn(OdmProjectDtoOut.builder().status("APPROVED").build());

        OdmProjectDtoOut result = service().setOdmStatus(id, new OdmStatusUpdateDtoIn("APPROVED"));

        assertThat(project.getStatus()).isEqualTo("APPROVED");
        assertThat(result.getStatus()).isEqualTo("APPROVED");
        verify(odmRepo).save(project);
    }

    @Test
    void resolve_setsResolvedStatusAndResolution() {
        UUID id = UUID.randomUUID();
        var ticket = SupportTicketEntity.builder().status("OPEN").build();
        when(ticketRepo.findById(id)).thenReturn(Optional.of(ticket));
        when(ticketRepo.save(ticket)).thenReturn(ticket);
        when(mapper.toTicketDto(ticket)).thenReturn(SupportTicketDtoOut.builder().status("RESOLVED").build());

        SupportTicketDtoOut result = service().resolve(id, new SupportTicketResolveDtoIn("done"));

        assertThat(ticket.getStatus()).isEqualTo("RESOLVED");
        assertThat(ticket.getResolution()).isEqualTo("done");
        assertThat(result.getStatus()).isEqualTo("RESOLVED");
    }

    @Test
    void resolve_throwsWhenTicketNotFound() {
        UUID id = UUID.randomUUID();
        when(ticketRepo.findById(id)).thenReturn(Optional.empty());
        PlatformExtrasService svc = service();
        var dto = new SupportTicketResolveDtoIn("x");

        assertThatThrownBy(() -> svc.resolve(id, dto))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void shippingCalculator_returnsFourCarrierRows() {
        var req = new ShippingCalculatorDtoIn(1000, 2, "US", null, "US", 2, "STANDARD");

        List<ShippingRateDtoOut> rows = service().shippingCalculator(req);

        assertThat(rows).extracting(ShippingRateDtoOut::getMethod)
                .containsExactly("STANDARD", "EXPRESS", "AIR", "SEA");
    }

    @Test
    void carbonFootprint_reportsSeaAsGreenest() {
        var req = new ShippingCalculatorDtoIn(1000, 1, "US", null, "US", 1, "SEA");

        CarbonFootprintDtoOut esg = service().carbonFootprint(req);

        assertThat(esg.getGreenestMethod()).isEqualTo("SEA");
        assertThat(esg.getCarbonKg()).isNotNull();
    }
}
