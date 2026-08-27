--liquibase formatted sql

--changeset nexadrop:v139-001-order-shipment-declaracion splitStatements:false
-- QUÉ SE LE DECLARÓ AL TRANSPORTISTA POR CADA BULTO. Al crear una guía internacional se le transmite a
-- YunExpress una declaración completa: el destinatario y, línea a línea, la descripción en inglés y en
-- chino, la partida arancelaria, la cantidad, el valor y el peso. De todo eso solo se guardaba el
-- RESULTADO (guía, canal, peso, valor declarado y URL de etiqueta), así que cuando la aduana o el
-- transportista rechazaban un envío nadie podía comprobar QUÉ se había declarado sin entrar al panel de
-- YunExpress —y allí no hay histórico propio ni queda constancia de lo que salió de aquí.
--
-- Se archiva como jsonb y no en tablas normalizadas a propósito: es una copia inmutable de lo que se
-- transmitió en un instante concreto. Normalizarla obligaría a migrarla cada vez que el transportista
-- cambie un campo, y reescribir lo que ya se declaró es justo lo contrario de lo que hace falta aquí.
--
-- NUNCA lleva credenciales ni las cabeceras de firma de la llamada: solo el contenido de la declaración.
--
-- Queda a NULL en los envíos anteriores a este cambio. No es un problema: la ficha del pedido
-- sencillamente no pinta el bloque, igual que ya ocurre con el contenido del bulto (v136).
ALTER TABLE order_shipment ADD COLUMN IF NOT EXISTS declaration_json jsonb;

COMMENT ON COLUMN order_shipment.declaration_json IS
    'Declaración transmitida al transportista al crear esta guía: destinatario y líneas declaradas. '
    'Sin credenciales ni cabeceras de firma. NULL en envíos anteriores a v139.';
