package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.PackOrderExportService;
import com.nexaplatform.dropshipping.application.service.PackOrderExportService.PackOrderPlan;
import com.nexaplatform.dropshipping.application.service.PackOrderExportService.PackOrderRow;
import com.nexaplatform.dropshipping.application.service.SupplierPurchaseService;
import com.nexaplatform.dropshipping.domain.enums.PackServiceType;
import com.nexaplatform.dropshipping.domain.enums.SupplierPurchaseStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.repository.OrderRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierPurchaseEntity;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PackOrderExportServiceTest {

    @Mock
    SupplierPurchaseService purchaseService;
    @Mock
    OrderRepository orderRepository;

    @InjectMocks
    PackOrderExportService service;

    private static final UUID ORDER_ID = UUID.randomUUID();

    private static Order order(String tracking) {
        return Order.builder().id(ORDER_ID).orderNumber("NX-100").trackingNumber(tracking).build();
    }

    private static SupplierPurchaseEntity purchase(String domestic) {
        return SupplierPurchaseEntity.builder().id(UUID.randomUUID()).orderId(ORDER_ID)
                .status(SupplierPurchaseStatus.IN_TRANSIT).warehouseCode("CNCHASHAN")
                .domesticTracking(domestic).build();
    }

    @Test
    void unSoloBultoSeReempaquetaYNoLlevaNota() {
        // «更换包装» no admite observaciones: mandarlas invalidaría la fila entera.
        when(purchaseService.openQueue()).thenReturn(List.of(purchase("SF123")));
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order("YT999")));

        PackOrderPlan plan = service.plan();

        assertThat(plan.issues()).isEmpty();
        assertThat(plan.rows()).hasSize(1);
        PackOrderRow row = plan.rows().get(0);
        assertThat(row.service()).isEqualTo(PackServiceType.REPACKAGING);
        assertThat(row.service().code()).isEqualTo("更换包装");
        assertThat(row.remarks()).isEmpty();
        assertThat(row.removeInnerItems()).isTrue();
    }

    @Test
    void variosBultosDelMismoPedidoSeConsolidanBajoUnSoloYt() {
        when(purchaseService.openQueue()).thenReturn(List.of(purchase("SF123"), purchase("JT456")));
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order("YT999")));

        PackOrderPlan plan = service.plan();

        assertThat(plan.rows()).hasSize(1);
        PackOrderRow row = plan.rows().get(0);
        assertThat(row.service()).isEqualTo(PackServiceType.CONSOLIDATE);
        assertThat(row.domesticNumbers()).containsExactly("SF123", "JT456");
        assertThat(row.ytNumbers()).containsExactly("YT999");
    }

    @Test
    void sinSeguimientoNacionalNoSeExportaYSeExplicaPorQue() {
        when(purchaseService.openQueue()).thenReturn(List.of(purchase(null)));
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order("YT999")));

        PackOrderPlan plan = service.plan();

        assertThat(plan.rows()).isEmpty();
        assertThat(plan.issues()).singleElement()
                .satisfies(i -> assertThat(i.reason()).contains("seguimiento nacional"));
    }

    @Test
    void detectaLosNumerosCruzadosEntreNacionalYYt() {
        // Causa real del OMS: «the domestic tracking number and the YT tracking number are registered
        // in the wrong positions».
        when(purchaseService.openQueue()).thenReturn(List.of(purchase("YT888")));
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order("YT999")));

        PackOrderPlan plan = service.plan();

        assertThat(plan.rows()).isEmpty();
        assertThat(plan.issues()).singleElement()
                .satisfies(i -> assertThat(i.reason()).contains("campos cruzados"));
    }

    @Test
    void sinGuiaInternacionalNoHayNumeroYtQueRegistrar() {
        when(purchaseService.openQueue()).thenReturn(List.of(purchase("SF123")));
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order(null)));

        PackOrderPlan plan = service.plan();

        assertThat(plan.rows()).isEmpty();
        assertThat(plan.issues()).singleElement()
                .satisfies(i -> assertThat(i.reason()).contains("no tiene número YT"));
    }

    @Test
    void unaCompraYaReempaquetadaNoSeVuelveASubir() {
        SupplierPurchaseEntity yaHecha = purchase("SF123");
        yaHecha.setPackOrderNo("PK-1");
        when(purchaseService.openQueue()).thenReturn(List.of(yaHecha));
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order("YT999")));

        PackOrderPlan plan = service.plan();

        assertThat(plan.rows()).isEmpty();
        assertThat(plan.issues()).singleElement()
                .satisfies(i -> assertThat(i.reason()).contains("duplicado"));
    }

    @Test
    void lasComprasCanceladasNoCuentanComoBultos() {
        SupplierPurchaseEntity viva = purchase("SF123");
        SupplierPurchaseEntity muerta = purchase("JT456");
        muerta.setStatus(SupplierPurchaseStatus.CANCELLED);
        when(purchaseService.openQueue()).thenReturn(List.of(viva, muerta));
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order("YT999")));

        PackOrderPlan plan = service.plan();

        assertThat(plan.rows()).hasSize(1);
        // Un solo bulto real: consolidar habría costado 3 CNY de más por pedido.
        assertThat(plan.rows().get(0).service()).isEqualTo(PackServiceType.REPACKAGING);
        assertThat(plan.rows().get(0).domesticNumbers()).containsExactly("SF123");
    }

    @Test
    void elFicheroLlevaElEncabezadoExactoDeLaPlantillaOficial() throws IOException {
        PackOrderRow row = new PackOrderRow(PackServiceType.CONSOLIDATE, "CNCHASHAN",
                List.of("SF123", "JT456"), List.of("YT999"), false, false, true, "Consolidar");

        byte[] xls = service.toXls(List.of(row));

        try (Workbook wb = new HSSFWorkbook(new ByteArrayInputStream(xls))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("订单批量上传");
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("服务类型/Service Type");
            assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("仓库/Warehouse");
            assertThat(sheet.getRow(0).getCell(2).getStringCellValue()).startsWith("国内单号/Domestic No.");
            assertThat(sheet.getRow(0).getCell(3).getStringCellValue()).startsWith("YT单号/Tracking No.");

            // Varios números en UNA celda separados por salto de línea, como pide la plantilla.
            assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("SF123\nJT456");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("多个快递一个YT单");
            // El OMS espera «1»/«0» literales, no celdas booleanas.
            assertThat(sheet.getRow(1).getCell(4).getStringCellValue()).isEqualTo("0");
            assertThat(sheet.getRow(1).getCell(6).getStringCellValue()).isEqualTo("1");
        }
    }

    @Test
    void elServicioSeEligePorNumeroDeBultosYCadaUnoTieneSuTarifa() {
        assertThat(PackServiceType.forIncomingParcels(1)).isEqualTo(PackServiceType.REPACKAGING);
        assertThat(PackServiceType.forIncomingParcels(3)).isEqualTo(PackServiceType.CONSOLIDATE);
        assertThat(PackServiceType.REPACKAGING.priceCnyCents()).isEqualTo(200L);
        assertThat(PackServiceType.CONSOLIDATE.priceCnyCents()).isEqualTo(500L);
        // Solo etiquetar no admite ni valor añadido ni nota (hoja «服务类型» de la plantilla).
        assertThat(PackServiceType.LABEL_ONLY.allowsValueAdded()).isFalse();
        assertThat(PackServiceType.LABEL_ONLY.allowsRemarks()).isFalse();
        assertThat(PackServiceType.REPACKAGING.allowsRemarks()).isFalse();
        assertThat(PackServiceType.CUSTOM.allowsRemarks()).isTrue();
    }
}
