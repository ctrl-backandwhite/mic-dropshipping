package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.domain.enums.PackServiceType;
import com.nexaplatform.dropshipping.domain.enums.PackWarehouse;
import com.nexaplatform.dropshipping.domain.enums.SupplierPurchaseStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.repository.OrderRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierPurchaseEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Construye el fichero de importación masiva de órdenes de re-empaquetado de Yunfulfillment.
 *
 * <p>Su OMS no tiene (todavía) API abierta para nosotros, pero sí admite subir hasta 1000 órdenes de
 * una vez en un .xls con un formato fijo. Esto genera ese fichero a partir de las compras ya
 * despachadas por el proveedor, de modo que el trabajo manual se reduce a subirlo.
 *
 * <p>Lo importante no es escribir el Excel sino <b>no escribir filas malas</b>: el OMS rechaza la orden
 * y el bulto se queda en el almacén corriendo hacia los 30 días que acaban en destrucción. Por eso cada
 * pedido pasa antes por {@link #validate}, que reproduce las causas de excepción que el propio OMS
 * enumera en su filtro de anomalías.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PackOrderExportService {

    /** Tope del OMS: «A maximum of 1000 data entries can be imported simultaneously». */
    public static final int MAX_ROWS = 1000;

    /**
     * Encabezado EXACTO de la plantilla oficial. La instrucción del OMS es literal: «Please do not
     * change the template content». Se replica carácter a carácter, incluidos los paréntesis bilingües.
     */
    private static final String[] HEADERS = {
        "服务类型/Service Type",
        "仓库/Warehouse",
        "国内单号/Domestic No.(多个单号请在单元格内换行 Multiple tracking numbers, please line break within the cell)",
        "YT单号/Tracking No.(多个单号请在单元格内换行 Multiple tracking numbers, please line break within the cell)",
        "产品拍照（填写内容默认需要此服务，无法填写内容时，请填写1或0，1：需要，0：不需要）/Product photography (this service is required by default when filling in the content. If unable to fill in the content, please fill in 1 or 0, 1: required, 0: not required)",
        "开箱质检（填写内容默认需要此服务，无法填写内容时，请填写1或0，1：需要，0：不需要）/Open box quality inspection (this service is required by default when filling in the content. If unable to fill in the content, please fill in 1 or 0, 1: required, 0: not required)",
        "取出内件（填写内容默认需要此服务，无法填写内容时，请填写1或0，1：需要，0：不需要）/Retrieve the contents (this service is required by default when filling in the content, please fill in 1 or 0 when unable to fill in the content, 1: required, 0: not required)",
        "订单备注/Order Remarks",
    };

    private static final String SHEET_NAME = "订单批量上传";

    /** Los números YT del transportista empiezan siempre por «YT». Sirve para detectar campos cruzados. */
    private static final String YT_PREFIX = "YT";

    private final SupplierPurchaseService supplierPurchaseService;
    private final OrderRepository orderRepository;

    /** Una fila del fichero: exactamente lo que el OMS espera en cada columna. */
    public record PackOrderRow(PackServiceType service, String warehouse, List<String> domesticNumbers,
                               List<String> ytNumbers, boolean photography, boolean qualityInspection,
                               boolean removeInnerItems, String remarks) {
    }

    /** Un pedido que NO puede exportarse todavía, con el motivo en claro para que el admin lo arregle. */
    public record PackOrderIssue(UUID orderId, String orderNumber, String reason) {
    }

    /** Lo exportable y lo que se queda fuera. Nunca se descarta en silencio. */
    public record PackOrderPlan(List<PackOrderRow> rows, List<UUID> orderIds, List<PackOrderIssue> issues) {
    }

    /**
     * Planifica el fichero con todo lo que está listo para reempaquetar.
     *
     * <p>Un pedido está listo cuando todos sus bultos han salido del proveedor (hay seguimiento
     * nacional) y la guía internacional ya existe (hay número YT). Lo que no cumple se devuelve en
     * {@code issues} con el motivo: un pedido excluido sin explicación es un bulto que nadie va a
     * reclamar hasta que lo destruyan.
     */
    @Transactional(readOnly = true)
    public PackOrderPlan plan() {
        Map<UUID, List<SupplierPurchaseEntity>> byOrder = new LinkedHashMap<>();
        for (SupplierPurchaseEntity p : supplierPurchaseService.exportQueue()) {
            byOrder.computeIfAbsent(p.getOrderId(), k -> new ArrayList<>()).add(p);
        }
        List<PackOrderRow> rows = new ArrayList<>();
        List<UUID> orderIds = new ArrayList<>();
        List<PackOrderIssue> issues = new ArrayList<>();
        for (Map.Entry<UUID, List<SupplierPurchaseEntity>> e : byOrder.entrySet()) {
            Order order = orderRepository.findById(e.getKey()).orElse(null);
            if (order == null) {
                continue;
            }
            if (rows.size() >= MAX_ROWS) {
                // No se recorta en silencio: el resto queda anotado para la siguiente tanda.
                issues.add(new PackOrderIssue(order.getId(), order.getOrderNumber(),
                        "Supera las " + MAX_ROWS + " órdenes por fichero; va en la siguiente tanda"));
                continue;
            }
            List<String> problems = validate(order, e.getValue());
            if (!problems.isEmpty()) {
                problems.forEach(r -> issues.add(new PackOrderIssue(order.getId(), order.getOrderNumber(), r)));
                continue;
            }
            rows.add(toRow(order, e.getValue()));
            orderIds.add(order.getId());
        }
        log.info("Plan de re-empaquetado: {} fila(s) exportables, {} pendiente(s) de arreglar",
                rows.size(), issues.size());
        return new PackOrderPlan(rows, orderIds, issues);
    }

    /**
     * Comprueba lo que el OMS rechazaría, antes de mandárselo.
     *
     * <p>Cada regla corresponde a una causa real de su listado de anomalías. Devolver una lista y no
     * lanzar excepción es deliberado: interesa ver TODOS los problemas del pedido de una vez, no el
     * primero.
     */
    public List<String> validate(Order order, List<SupplierPurchaseEntity> purchases) {
        List<String> problems = new ArrayList<>();
        List<SupplierPurchaseEntity> pending = purchases.stream()
                .filter(p -> p.getStatus() != SupplierPurchaseStatus.CANCELLED)
                .toList();
        if (pending.isEmpty()) {
            problems.add("Todas las compras del pedido están canceladas");
            return problems;
        }
        for (SupplierPurchaseEntity p : pending) {
            if (isBlank(p.getDomesticTracking())) {
                problems.add("Falta el seguimiento nacional de la compra " + p.getId()
                        + " (el proveedor aún no ha enviado)");
            } else if (p.getDomesticTracking().startsWith(YT_PREFIX)) {
                // Causa literal del OMS: «The domestic tracking number and the YT tracking number are
                // registered in the wrong positions».
                problems.add("El seguimiento nacional " + p.getDomesticTracking()
                        + " parece un número YT: campos cruzados");
            }
            if (p.getPackOrderNo() != null) {
                problems.add("La compra " + p.getId() + " ya tiene orden de re-empaquetado ("
                        + p.getPackOrderNo() + "): sería un duplicado");
            }
        }
        if (isBlank(order.getTrackingNumber())) {
            // Decir solo «falta la guía» dejaba al admin sin saber qué hacer: la guía no se emite al
            // registrar el seguimiento chino, sino al despachar el pedido, y eso ocurre en otra
            // pantalla. Sin esta indicación el fichero no se activaba nunca y no había forma de
            // averiguar por qué.
            problems.add("El pedido no tiene número YT: despáchalo en Órdenes y la guía "
                    + "internacional se emite sola en cuanto todos sus bultos van al almacén");
        } else if (!order.getTrackingNumber().startsWith(YT_PREFIX)) {
            problems.add("El número de seguimiento " + order.getTrackingNumber()
                    + " no parece un YT de YunExpress");
        }
        // El OMS rechaza el fichero ENTERO si una fila trae un almacén no operativo («Warehouse does
        // not exist»), así que la fila mala tiene que quedarse fuera antes de generarlo.
        PackWarehouse warehouse = PackWarehouse.fromCode(pending.get(0).getWarehouseCode());
        if (!warehouse.usableForImport()) {
            problems.add("El almacén " + pending.get(0).getWarehouseCode()
                    + " no admite órdenes de re-empaquetado: usa CNCHASHAN o CNJIASHAN");
        }
        return problems;
    }

    private PackOrderRow toRow(Order order, List<SupplierPurchaseEntity> purchases) {
        List<String> domestic = purchases.stream()
                .filter(p -> p.getStatus() != SupplierPurchaseStatus.CANCELLED)
                .map(SupplierPurchaseEntity::getDomesticTracking)
                .toList();
        PackServiceType service = PackServiceType.forIncomingParcels(domestic.size());
        String warehouse = purchases.get(0).getWarehouseCode();
        // El re-empaquetado NO admite nota («只更换包装发货»): mandarla invalidaría la fila. Con varios
        // bultos sí se acepta, y ahí conviene decir que van juntos.
        String remarks = service.allowsRemarks() ? "Consolidar en un solo envío" : "";
        return new PackOrderRow(service, warehouse, domestic, List.of(order.getTrackingNumber()),
                false, false, true, remarks);
    }

    /**
     * Escribe el .xls en memoria.
     *
     * <p>Formato BIFF8 (HSSF) y no XLSX: el OMS solo acepta la extensión {@code .xls} y rechaza el
     * formato nuevo aunque se le cambie el nombre al fichero.
     */
    public byte[] toXls(List<PackOrderRow> rows) {
        try (Workbook wb = new HSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(SHEET_NAME);
            Row header = sheet.createRow(0);
            for (int c = 0; c < HEADERS.length; c++) {
                header.createCell(c).setCellValue(HEADERS[c]);
            }
            // Varios números van en UNA celda separados por salto de línea, tal y como pide la plantilla
            // («请在单元格内换行»). Sin ajuste de línea el valor se guarda igual, pero es ilegible al
            // revisarlo antes de subirlo.
            CellStyle wrapped = wb.createCellStyle();
            wrapped.setWrapText(true);
            for (int i = 0; i < rows.size(); i++) {
                PackOrderRow r = rows.get(i);
                Row row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue(r.service().code());
                row.createCell(1).setCellValue(r.warehouse());
                multiline(row.createCell(2), r.domesticNumbers(), wrapped);
                multiline(row.createCell(3), r.ytNumbers(), wrapped);
                row.createCell(4).setCellValue(flag(r.photography()));
                row.createCell(5).setCellValue(flag(r.qualityInspection()));
                row.createCell(6).setCellValue(flag(r.removeInnerItems()));
                row.createCell(7).setCellValue(r.remarks() == null ? "" : r.remarks());
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo generar el fichero de re-empaquetado", e);
        }
    }

    private static void multiline(Cell cell, List<String> values, CellStyle style) {
        cell.setCellValue(String.join("\n", values));
        cell.setCellStyle(style);
    }

    /**
     * Marca un servicio de valor añadido.
     *
     * <p>«1» para pedirlo y celda VACÍA para no pedirlo, que es como lo escribe la propia plantilla en
     * su hoja de ejemplos: donde no quieren el servicio dejan el hueco en blanco, nunca un cero. La
     * primera importación real fue con «0» y la orden salió listando los tres servicios, así que el
     * cero no se comporta como un «no» — y cada uno cuesta 0,5 CNY por pieza.
     */
    private static String flag(boolean on) {
        return on ? "1" : "";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
