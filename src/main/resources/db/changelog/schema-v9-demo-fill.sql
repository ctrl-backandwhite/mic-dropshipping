--liquibase formatted sql

--changeset nexadrop:v9-fill-suppliers splitStatements:false runOnChange:false endDelimiter:;
--comment: Demo data fill — every collection >= 20 records so the admin UI is exercisable.

DO $$
DECLARE cur int;
BEGIN
    SELECT count(*) INTO cur FROM supplier;
    IF cur < 20 THEN
        INSERT INTO supplier (id, source, external_id, name, name_zh, country, city, rating, years_active, verified, trust_pass, profile_url, created_at, updated_at)
        SELECT gen_random_uuid(), '1688',
               'sup-extra-' || g, 'Demo Supplier ' || g, '示例供应商' || g, 'CN',
               (ARRAY['Yiwu','Shenzhen','Guangzhou','Ningbo','Hangzhou','Dongguan','Foshan','Shanghai','Xiamen','Wenzhou','Qingdao','Tianjin'])[1 + (g % 12)],
               (3.5 + random() * 1.5)::numeric(3,2),
               (3 + (g % 12))::int,
               (random() < 0.8), (random() < 0.6),
               'https://demo-supplier-' || g || '.1688.com',
               now() - (g || ' days')::interval, now()
        FROM generate_series(cur + 1, 20) g;
    END IF;
END $$;

--changeset nexadrop:v9-fill-warehouses splitStatements:false endDelimiter:;
DO $$
DECLARE cur int;
BEGIN
    SELECT count(*) INTO cur FROM warehouse;
    IF cur < 20 THEN
        INSERT INTO warehouse (id, code, name, country, city, active, created_at, updated_at)
        SELECT gen_random_uuid(), c.code, c.name, c.country, c.city, true, now(), now()
        FROM (VALUES
            ('AU-SYD','Sydney Hub','AU','Sydney'),
            ('IN-BLR','Bangalore Hub','IN','Bangalore'),
            ('AE-DXB','Dubai DC','AE','Dubai'),
            ('BR-SAO','Sao Paulo DC','BR','São Paulo'),
            ('CO-BOG','Bogota DC','CO','Bogotá'),
            ('FR-PAR','Paris DC','FR','Paris'),
            ('IT-MIL','Milan DC','IT','Milan'),
            ('NL-AMS','Amsterdam DC','NL','Amsterdam')
        ) AS c(code,name,country,city)
        WHERE NOT EXISTS (SELECT 1 FROM warehouse w WHERE w.code = c.code);
    END IF;
END $$;

--changeset nexadrop:v9-fill-mentor-profiles splitStatements:false endDelimiter:;
DO $$
DECLARE cur int; candidates uuid[]; g int;
BEGIN
    SELECT count(*) INTO cur FROM mentor_profile;
    IF cur < 20 THEN
        SELECT array_agg(id) INTO candidates FROM (
            SELECT id FROM users
            WHERE id NOT IN (SELECT user_id FROM mentor_profile)
            LIMIT 20
        ) s;
        IF candidates IS NULL THEN candidates := ARRAY[]::uuid[]; END IF;
        FOR g IN 1..least(20 - cur, coalesce(array_length(candidates,1),0)) LOOP
            INSERT INTO mentor_profile (id, user_id, headline, expertise, languages, hourly_rate_usd_cents, bio, timezone, active, created_at, updated_at)
            VALUES (
                gen_random_uuid(), candidates[g],
                (ARRAY['Cross-border Shopify expert','TikTok Shop growth coach','1688 sourcing specialist','Brand storytelling mentor','Logistics + 3PL consultant','Paid social strategist','Email & retention pro','POD launch coach','LATAM market entry','Conversion rate optimizer','Customer support ops','Marketplace listings','Pricing strategist','Retention growth'])[1 + ((g - 1) % 14)],
                '["dropshipping","ecommerce","ads"]'::jsonb,
                '["en","es","pt"]'::jsonb,
                (4000 + (g * 750)),
                'Mentor profile #' || g || ' with 10+ years across DTC and cross-border.',
                'Europe/Madrid',
                true, now(), now()
            );
        END LOOP;
    END IF;
END $$;

--changeset nexadrop:v9-fill-sourcing-requests splitStatements:false endDelimiter:;
DO $$
DECLARE cur int; usr uuid; i int;
BEGIN
    SELECT count(*) INTO cur FROM sourcing_request;
    IF cur < 25 THEN
        FOR i IN cur+1..25 LOOP
            SELECT id INTO usr FROM users ORDER BY random() LIMIT 1;
            INSERT INTO sourcing_request (id, user_id, source, external_id, source_url, title_hint, status, plan_quota, notes, created_at, updated_at)
            VALUES (
                gen_random_uuid(), usr,
                (ARRAY['1688','TAOBAO','ALIEXPRESS','EBAY'])[1 + (i % 4)],
                'ext-' || i,
                'https://detail.1688.com/offer/' || (700000000 + i) || '.html',
                (ARRAY['Pulsera magnetica','Auriculares BT','Lampara LED smart','Cargador rapido USB-C','Funda iPhone matte','Mochila waterproof','Bolso shopper','Sudadera oversize','Botella termica','Difusor aroma','Vela soja','Espejo aro luz'])[1 + (i % 12)] || ' #' || i,
                (ARRAY['PENDING','QUOTING','QUOTED','SELECTED','PURCHASED','CANCELLED'])[1 + (i % 6)],
                (ARRAY['FREE','PLUS','PRIME'])[1 + (i % 3)],
                'Demo sourcing request #' || i,
                now() - (i || ' days')::interval, now()
            );
        END LOOP;
    END IF;
END $$;

--changeset nexadrop:v9-fill-sourcing-quotes splitStatements:false endDelimiter:;
DO $$
DECLARE cur int; req uuid; agent uuid; i int;
BEGIN
    SELECT count(*) INTO cur FROM sourcing_quote;
    IF cur < 25 THEN
        FOR i IN cur+1..25 LOOP
            SELECT id INTO req FROM sourcing_request ORDER BY random() LIMIT 1;
            SELECT id INTO agent FROM agent_profile ORDER BY random() LIMIT 1;
            EXIT WHEN req IS NULL OR agent IS NULL;
            INSERT INTO sourcing_quote (id, request_id, agent_id, price_usd_cents, eta_days, moq, notes, status, created_at, updated_at)
            VALUES (
                gen_random_uuid(), req, agent,
                (250 + (i * 37) % 1500) * 10,
                7 + (i % 21),
                (ARRAY[1,5,10,50,100])[1 + (i % 5)],
                'Quote ' || i || ' - includes OEM packaging',
                (ARRAY['OPEN','SELECTED','REJECTED'])[1 + (i % 3)],
                now() - (i || ' hours')::interval, now()
            );
        END LOOP;
    END IF;
END $$;

--changeset nexadrop:v9-fill-pod-designs splitStatements:false endDelimiter:;
DO $$
DECLARE cur int; usr uuid; prod uuid; i int;
BEGIN
    SELECT count(*) INTO cur FROM pod_design;
    IF cur < 25 THEN
        FOR i IN cur+1..25 LOOP
            SELECT id INTO usr FROM users WHERE role IN ('USER','PARTNER') ORDER BY random() LIMIT 1;
            SELECT id INTO prod FROM product WHERE base_price IS NOT NULL ORDER BY random() LIMIT 1;
            EXIT WHEN usr IS NULL OR prod IS NULL;
            INSERT INTO pod_design (id, user_id, product_id, name, canvas_json, mockup_url, status, ai_prompt, created_at, updated_at)
            VALUES (
                gen_random_uuid(), usr, prod,
                (ARRAY['Camel hoodie Sunrise','White tee Tokyo','Mug Botanical','Tote Y2K','Canvas Aurora','Tee Surf','Hoodie Bold','Mug Lo-fi','Cap Vintage','Stickers Indie','Beanie Snow','Tee 80s'])[1 + (i % 12)] || ' #' || i,
                '{}'::jsonb,
                NULL,
                (ARRAY['DRAFT','APPROVED','PUBLISHED'])[1 + (i % 3)],
                'Minimal line-art design with brand colors, prompt #' || i,
                now() - (i || ' hours')::interval, now()
            );
        END LOOP;
    END IF;
END $$;

--changeset nexadrop:v9-fill-odm-projects splitStatements:false endDelimiter:;
DO $$
DECLARE cur int; usr uuid; i int;
BEGIN
    SELECT count(*) INTO cur FROM odm_project;
    IF cur < 25 THEN
        FOR i IN cur+1..25 LOOP
            SELECT id INTO usr FROM users WHERE role IN ('USER','PARTNER') ORDER BY random() LIMIT 1;
            EXIT WHEN usr IS NULL;
            INSERT INTO odm_project (id, user_id, kind, title, brief, budget_usd_cents, sla_days, status, created_at, updated_at)
            VALUES (
                gen_random_uuid(), usr,
                (ARRAY['PRIVATE_LABEL','PACKAGING','POD','OEM'])[1 + (i % 4)],
                (ARRAY['Linea cosmetica organica','Funda eco-friendly','Mochila premium','Set yoga','Pack mascotas','Termo viajero','Lampara minimalista','Vajilla bambu'])[1 + (i % 8)] || ' #' || i,
                'Brief tecnico ' || i || ': materiales sustentables, MOQ 500.',
                (5000 + (i * 850)) * 100,
                14 + (i % 60),
                (ARRAY['INTAKE','SCOPING','DESIGN','SAMPLING','PRODUCTION','SHIPPED','CANCELLED'])[1 + (i % 7)],
                now() - (i || ' days')::interval, now()
            );
        END LOOP;
    END IF;
END $$;

--changeset nexadrop:v9-fill-support-tickets splitStatements:false endDelimiter:;
DO $$
DECLARE cur int; usr uuid; i int;
BEGIN
    SELECT count(*) INTO cur FROM support_ticket;
    IF cur < 25 THEN
        FOR i IN cur+1..25 LOOP
            SELECT id INTO usr FROM users ORDER BY random() LIMIT 1;
            INSERT INTO support_ticket (id, user_id, kind, subject, body, status, priority, created_at, updated_at)
            VALUES (
                gen_random_uuid(), usr,
                (ARRAY['ORDER','BILLING','SHIPPING','PRODUCT','OTHER'])[1 + (i % 5)],
                (ARRAY['Cuando llega mi pedido','Diferencia en factura','Producto recibido danado','Como cambiar el plan','Conectar Shopify','Error 500 en intel','Solicito reembolso','Cambio de talla','Duda con POD','Ayuda con sourcing','Wallet no carga','Activar 2FA','Eliminar cuenta','Cargo no reconocido'])[1 + (i % 14)] || ' #' || i,
                'Cliente reporta el siguiente caso #' || i || '.',
                (ARRAY['OPEN','PENDING','RESOLVED','CLOSED'])[1 + (i % 4)],
                (ARRAY['LOW','NORMAL','HIGH','URGENT'])[1 + (i % 4)],
                now() - (i || ' hours')::interval, now()
            );
        END LOOP;
    END IF;
END $$;

--changeset nexadrop:v9-fill-intel-alerts splitStatements:false endDelimiter:;
DO $$
DECLARE cur int; usr uuid; i int;
BEGIN
    SELECT count(*) INTO cur FROM intelligence_alert;
    IF cur < 22 THEN
        FOR i IN cur+1..22 LOOP
            SELECT id INTO usr FROM users ORDER BY random() LIMIT 1;
            INSERT INTO intelligence_alert (id, user_id, keyword, channel, threshold_score, active, created_at, updated_at)
            VALUES (
                gen_random_uuid(), usr,
                (ARRAY['phone case','smartwatch','running shoes','vacuum','candle','LED strip','beanie','kettle','desk lamp','tote bag','yoga mat','massage gun'])[1 + (i % 12)],
                (ARRAY['EMAIL','PUSH','SLACK'])[1 + (i % 3)],
                (0.55 + random() * 0.4)::numeric(6,3),
                (i % 5 <> 0),
                now() - (i || ' days')::interval, now()
            );
        END LOOP;
    END IF;
END $$;

--changeset nexadrop:v9-fill-academy-enrollments splitStatements:false endDelimiter:;
DO $$
DECLARE cur int; usr uuid; course uuid; i int;
BEGIN
    SELECT count(*) INTO cur FROM academy_enrollment;
    IF cur < 25 THEN
        FOR i IN cur+1..25 LOOP
            SELECT id INTO usr FROM users ORDER BY random() LIMIT 1;
            SELECT id INTO course FROM academy_course ORDER BY random() LIMIT 1;
            EXIT WHEN course IS NULL;
            INSERT INTO academy_enrollment (id, user_id, course_id, progress_pct, completed_at, created_at, updated_at)
            VALUES (
                gen_random_uuid(), usr, course,
                (random() * 100)::numeric(5,2),
                CASE WHEN random() > 0.55 THEN now() - (i || ' days')::interval ELSE NULL END,
                now() - ((i*2) || ' days')::interval, now()
            )
            ON CONFLICT DO NOTHING;
        END LOOP;
    END IF;
END $$;

--changeset nexadrop:v9-fill-mentor-bookings splitStatements:false endDelimiter:;
DO $$
DECLARE cur int; mentor uuid; learner uuid; i int;
BEGIN
    SELECT count(*) INTO cur FROM mentor_booking;
    IF cur < 22 THEN
        FOR i IN cur+1..22 LOOP
            SELECT id INTO mentor FROM mentor_profile ORDER BY random() LIMIT 1;
            SELECT id INTO learner FROM users ORDER BY random() LIMIT 1;
            EXIT WHEN mentor IS NULL OR learner IS NULL;
            INSERT INTO mentor_booking (id, mentor_id, learner_id, starts_at, duration_min, status, topic, created_at, updated_at)
            VALUES (
                gen_random_uuid(), mentor, learner,
                now() + ((i - 10) || ' days')::interval + ((i*30) || ' minutes')::interval,
                (ARRAY[30,45,60,90])[1 + (i % 4)],
                (ARRAY['REQUESTED','CONFIRMED','COMPLETED','CANCELLED'])[1 + (i % 4)],
                (ARRAY['Audit conversion','TikTok Shop setup','Email warmup','Sourcing review','Launch checklist','LATAM expansion'])[1 + (i % 6)],
                now() - (i || ' hours')::interval, now()
            );
        END LOOP;
    END IF;
END $$;

--changeset nexadrop:v9-fill-affiliates splitStatements:false endDelimiter:;
DO $$
DECLARE cur int; usr uuid; i int;
BEGIN
    SELECT count(*) INTO cur FROM affiliate;
    IF cur < 22 THEN
        FOR i IN cur+1..22 LOOP
            SELECT id INTO usr FROM users WHERE id NOT IN (SELECT user_id FROM affiliate) ORDER BY random() LIMIT 1;
            EXIT WHEN usr IS NULL;
            INSERT INTO affiliate (id, user_id, code, earnings_usd_cents, payout_usd_cents, referrals_count, active, created_at, updated_at)
            VALUES (
                gen_random_uuid(), usr, 'AFF-' || lpad(i::text, 4, '0'),
                ((random() * 500000)::int), ((random() * 200000)::int),
                (5 + (i % 30)), true,
                now() - (i || ' days')::interval, now()
            );
        END LOOP;
    END IF;
END $$;

--changeset nexadrop:v9-fill-affiliate-referrals splitStatements:false endDelimiter:;
DO $$
DECLARE cur int; aff uuid; usr uuid; i int;
BEGIN
    SELECT count(*) INTO cur FROM affiliate_referral;
    IF cur < 25 THEN
        FOR i IN cur+1..25 LOOP
            SELECT id INTO aff FROM affiliate ORDER BY random() LIMIT 1;
            SELECT id INTO usr FROM users ORDER BY random() LIMIT 1;
            EXIT WHEN aff IS NULL OR usr IS NULL;
            INSERT INTO affiliate_referral (id, affiliate_id, referred_user_id, commission_usd_cents, converted, created_at, updated_at)
            VALUES (
                gen_random_uuid(), aff, usr,
                ((random() * 30000)::int),
                (random() > 0.4),
                now() - ((i*4) || ' hours')::interval, now()
            );
        END LOOP;
    END IF;
END $$;

--changeset nexadrop:v9-fill-partner-apps splitStatements:false endDelimiter:;
DO $$
DECLARE cur int; usr uuid; i int;
BEGIN
    SELECT count(*) INTO cur FROM partner_app;
    IF cur < 22 THEN
        FOR i IN cur+1..22 LOOP
            SELECT id INTO usr FROM users WHERE role IN ('PARTNER','ADMIN','OPERATOR') ORDER BY random() LIMIT 1;
            EXIT WHEN usr IS NULL;
            INSERT INTO partner_app (id, owner_user_id, name, description, client_id, client_secret_hash, scopes, webhook_url, webhook_secret, active, created_at, updated_at)
            VALUES (
                gen_random_uuid(), usr,
                'Demo Partner App ' || i,
                'Backend integration for partner #' || i,
                'partner-' || lpad(i::text, 3, '0'),
                '$2a$10$dummyhashfornx036demoseed' || lpad(i::text, 12, 'x'),
                (ARRAY['catalog.read','catalog.read,orders.write','shop.sync','catalog.read,shop.sync,orders.write'])[1 + (i % 4)],
                'https://demo-partner-' || i || '.local/webhooks/nx036',
                'whsec_' || lpad(i::text, 32, '0'),
                (i % 5 <> 0),
                now() - (i || ' days')::interval, now()
            );
        END LOOP;
    END IF;
END $$;

--changeset nexadrop:v9-fill-webhook-subscriptions splitStatements:false endDelimiter:;
DO $$
DECLARE cur int; usr uuid; i int;
BEGIN
    SELECT count(*) INTO cur FROM webhook_subscription;
    IF cur < 22 THEN
        FOR i IN cur+1..22 LOOP
            SELECT id INTO usr FROM users WHERE role IN ('PARTNER','ADMIN','OPERATOR') ORDER BY random() LIMIT 1;
            INSERT INTO webhook_subscription (id, user_id, name, target_url, secret, events, active, description, created_at, updated_at)
            VALUES (
                gen_random_uuid(), usr,
                'Demo subscription ' || i,
                'https://demo-partner-' || i || '.local/webhooks/' || (ARRAY['orders','shipments','catalog','sync'])[1 + (i % 4)],
                'whsec_' || lpad((i*7)::text, 32, '0'),
                jsonb_build_array(
                    (ARRAY['order.created','order.shipped','order.delivered','order.cancelled','catalog.updated','shop.synced'])[1 + (i % 6)]
                ),
                true,
                'Auto-generated demo webhook #' || i,
                now() - (i || ' days')::interval, now()
            );
        END LOOP;
    END IF;
END $$;

--changeset nexadrop:v9-fill-shop-listings splitStatements:false endDelimiter:;
DO $$
DECLARE cur int; conn uuid; prod uuid; i int;
BEGIN
    SELECT count(*) INTO cur FROM shop_product_listing;
    IF cur < 50 THEN
        FOR i IN cur+1..50 LOOP
            SELECT id INTO conn FROM user_shop_connection ORDER BY random() LIMIT 1;
            SELECT id INTO prod FROM product ORDER BY random() LIMIT 1;
            EXIT WHEN conn IS NULL OR prod IS NULL;
            INSERT INTO shop_product_listing (id, user_shop_connection_id, product_id, remote_product_id, status, last_pushed_at, created_at, updated_at)
            VALUES (
                gen_random_uuid(), conn, prod,
                'remote-' || lpad(i::text, 6, '0'),
                (ARRAY['DRAFT','PUSHED','SYNCING','ERROR'])[1 + (i % 4)],
                CASE WHEN i % 4 = 1 THEN now() - (i || ' hours')::interval ELSE NULL END,
                now() - (i || ' hours')::interval, now()
            )
            ON CONFLICT DO NOTHING;
        END LOOP;
    END IF;
END $$;
