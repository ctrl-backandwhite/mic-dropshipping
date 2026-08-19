--liquibase formatted sql

--changeset nexadrop:v149-prepago-iva-por-pais splitStatements:false
--
-- «El transportista prepaga» y «hay que pedirle el servicio V1» dejan de ser lo mismo.
--
-- Hasta hoy, marcar un país como prepagado implicaba mandarle a YunExpress el servicio adicional `V1`,
-- porque el código salía de una propiedad global. Y `V1` no es un prepago genérico: la especificación
-- oficial de su API lo define como «云途预缴IOSS附加服务费 (走欧盟税改IOSS流程的订单)» — el prepago del
-- IOSS de la reforma fiscal de la UE. Mandarlo a un destino fuera de la UE hace fallar el alta del envío,
-- y el pedido se queda cobrado y sin guía.
--
-- El problema es que hay destinos donde el transportista SÍ prepaga y NO hay que pedirle nada, porque el
-- canal ya va DDP por contrato. Revisando el cuadro oficial (云途整合渠道-2026-8-17.xlsx), nuestras dos
-- líneas contratadas —FZZXR y THPHR— ya liquidan el impuesto en origen en cuatro destinos:
--
--   * Emiratos      5 % del valor declarado, tope 270 USD
--   * Arabia Saudí  15 % + 34 RMB de gestión, valor entre 5 y 260 USD
--   * Canadá        18 % del valor declarado, franquicia 20 CAD, tope 99 USD
--   * México        33,5 % del valor declarado (desde el 11-ago-2025), valor entre 1 y 300 USD
--
-- Cita literal del contrato, hoja de THPHR, sobre México:
--   «2025年8月11日起，我司将依据客户申报的产品价值收取33.5%的税金，税金将向发件人收取。»
--
-- Por eso el código del servicio pasa a vivir en la fila del país: NULL significa «prepaga, pero no hay
-- que pedirle nada». Activar un país nuevo deja de necesitar un despliegue.
--
-- OJO con lo que esto NO arregla: en Canadá y México el transportista nos cobra 18 % y 33,5 %, mientras
-- que al cliente le repercutimos el 5 % y el 16 % de sus tipos fiscales. Esa diferencia sale del margen y
-- se corrige con el recargo de gestión (`handling_percent_bps`), que sigue a 0 en toda la tabla. Es una
-- decisión de precio, no de esquema, y se toma aparte.
ALTER TABLE country_customs_rule ADD COLUMN IF NOT EXISTS vat_prepay_service_code VARCHAR(16);

-- Los 27 de la UE conservan lo que ya hacían: prepago por IOSS con el servicio V1.
UPDATE country_customs_rule SET vat_prepay_service_code = 'V1'
 WHERE carrier_prepays_vat = true AND vat_prepay_service_code IS NULL;

-- Y los cuatro destinos donde el canal ya va DDP: prepagan, sin servicio que pedir.
UPDATE country_customs_rule
   SET carrier_prepays_vat = true, vat_prepay_service_code = NULL
 WHERE country_code IN ('AE', 'SA', 'CA', 'MX');
