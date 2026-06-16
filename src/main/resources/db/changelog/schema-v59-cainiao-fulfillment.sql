--liquibase formatted sql

--changeset nexadrop:v59-cainiao-fulfillment
-- Fulfillment con Cainiao: tracking de envío en la orden, timeline de eventos para el cliente y
-- catálogo de países/zonas soportados por Cainiao (la plataforma SOLO envía a países cubiertos;
-- esta tabla es la fuente de verdad para validar destino y calcular la tarifa por destino).

-- 1) Campos de envío/tracking en la orden
ALTER TABLE customer_order ADD COLUMN IF NOT EXISTS carrier               varchar(60);
ALTER TABLE customer_order ADD COLUMN IF NOT EXISTS tracking_number       varchar(120);
ALTER TABLE customer_order ADD COLUMN IF NOT EXISTS fulfillment_ref       varchar(120); -- id del envío en Cainiao
ALTER TABLE customer_order ADD COLUMN IF NOT EXISTS tracking_status       varchar(60);  -- último estado crudo de Cainiao
ALTER TABLE customer_order ADD COLUMN IF NOT EXISTS estimated_delivery_at timestamptz;
ALTER TABLE customer_order ADD COLUMN IF NOT EXISTS last_tracked_at       timestamptz;

-- 2) Timeline de eventos de tracking (lo que ve el cliente desde el pedido hasta la entrega)
CREATE TABLE IF NOT EXISTS order_tracking_event (
    id          uuid PRIMARY KEY,
    order_id    uuid NOT NULL REFERENCES customer_order(id) ON DELETE CASCADE,
    status      varchar(40) NOT NULL,          -- estado del dominio o crudo del carrier
    description varchar(300),
    location    varchar(160),
    source      varchar(20) NOT NULL DEFAULT 'CAINIAO', -- CAINIAO | SYSTEM | ADMIN
    occurred_at timestamptz NOT NULL DEFAULT now(),
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_tracking_event_order ON order_tracking_event(order_id, occurred_at);

-- 3) Zonas/países soportados por Cainiao + tarifa por destino y ventana de entrega estimada.
--    Un país que NO esté (o esté disabled) aquí => no se puede enviar con Cainiao.
CREATE TABLE IF NOT EXISTS cainiao_shipping_zone (
    id            uuid PRIMARY KEY,
    country_code  varchar(2)  NOT NULL UNIQUE,  -- ISO-3166-1 alpha-2
    country_name  varchar(80) NOT NULL,
    zone          varchar(30) NOT NULL,         -- ASIA | EU | NA | LATAM | OCEANIA | AFRICA
    base_cents    integer     NOT NULL,         -- tarifa base USD (céntimos)
    per_kg_cents  integer     NOT NULL,         -- coste por kg USD (céntimos)
    eta_min_days  integer     NOT NULL,
    eta_max_days  integer     NOT NULL,
    enabled       boolean     NOT NULL DEFAULT true,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz,
    created_by    varchar(120),
    updated_by    varchar(120)
);

--changeset nexadrop:v59-cainiao-zone-seed splitStatements:false
-- Seed de cobertura Cainiao (origen China → destinos habituales de dropshipping). Editable desde el admin.
INSERT INTO cainiao_shipping_zone (id, country_code, country_name, zone, base_cents, per_kg_cents, eta_min_days, eta_max_days)
VALUES
 (gen_random_uuid(),'ES','España','EU',499,350,8,18),
 (gen_random_uuid(),'PT','Portugal','EU',499,350,8,18),
 (gen_random_uuid(),'FR','Francia','EU',499,350,8,18),
 (gen_random_uuid(),'DE','Alemania','EU',499,350,8,18),
 (gen_random_uuid(),'IT','Italia','EU',499,350,8,18),
 (gen_random_uuid(),'NL','Países Bajos','EU',499,350,8,18),
 (gen_random_uuid(),'BE','Bélgica','EU',499,350,8,18),
 (gen_random_uuid(),'GB','Reino Unido','EU',549,380,9,20),
 (gen_random_uuid(),'PL','Polonia','EU',499,350,9,20),
 (gen_random_uuid(),'SE','Suecia','EU',549,380,9,20),
 (gen_random_uuid(),'IE','Irlanda','EU',549,380,9,20),
 (gen_random_uuid(),'US','Estados Unidos','NA',599,400,9,20),
 (gen_random_uuid(),'CA','Canadá','NA',649,420,10,22),
 (gen_random_uuid(),'MX','México','NA',649,430,11,24),
 (gen_random_uuid(),'BR','Brasil','LATAM',699,500,12,30),
 (gen_random_uuid(),'AR','Argentina','LATAM',749,520,14,32),
 (gen_random_uuid(),'CL','Chile','LATAM',699,500,12,30),
 (gen_random_uuid(),'CO','Colombia','LATAM',699,500,12,28),
 (gen_random_uuid(),'PE','Perú','LATAM',699,500,12,30),
 (gen_random_uuid(),'AU','Australia','OCEANIA',599,450,10,22),
 (gen_random_uuid(),'NZ','Nueva Zelanda','OCEANIA',649,470,11,24),
 (gen_random_uuid(),'JP','Japón','ASIA',399,250,5,12),
 (gen_random_uuid(),'KR','Corea del Sur','ASIA',399,250,5,12),
 (gen_random_uuid(),'SG','Singapur','ASIA',399,250,5,12),
 (gen_random_uuid(),'MY','Malasia','ASIA',399,260,6,14),
 (gen_random_uuid(),'TH','Tailandia','ASIA',399,260,6,14),
 (gen_random_uuid(),'ID','Indonesia','ASIA',429,280,7,16),
 (gen_random_uuid(),'PH','Filipinas','ASIA',429,280,7,16),
 (gen_random_uuid(),'VN','Vietnam','ASIA',399,260,6,14),
 (gen_random_uuid(),'AE','Emiratos Árabes Unidos','ASIA',499,320,7,16),
 (gen_random_uuid(),'SA','Arabia Saudí','ASIA',549,340,8,18),
 (gen_random_uuid(),'IL','Israel','ASIA',549,340,8,18),
 (gen_random_uuid(),'ZA','Sudáfrica','AFRICA',799,550,14,30)
ON CONFLICT (country_code) DO NOTHING;
