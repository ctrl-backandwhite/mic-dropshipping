--liquibase formatted sql

--changeset nexadrop:v41-affiliate-remove-staff
-- DROP-643/647/648: las cuentas admin y operador NO pueden ser afiliados. El seed las había
-- creado; las eliminamos (cascada borra códigos/atribuciones/conversiones/comisiones).
DELETE FROM affiliate a USING users u
WHERE a.user_id = u.id AND u.role IN ('ADMIN', 'OPERATOR');

--changeset nexadrop:v41-affiliate-reset-fake-earnings
-- DROP-646: los earnings/payout del seed eran ficticios (sin registros de comisión), por eso el
-- panel mostraba todo en "Pagado". Los reseteamos; se recalcularán desde comisiones reales.
UPDATE affiliate SET earnings_usd_cents = 0, payout_usd_cents = 0, referrals_count = 0;

--changeset nexadrop:v41-affiliate-demo-commissions splitStatements:false endDelimiter:;
-- Siembra comisiones reales con el ciclo completo (PENDING/APPROVED/PAID) para ~8 afiliados
-- cliente, usando pedidos existentes, para que el panel y el dashboard muestren datos coherentes.
DO $$
DECLARE
    affs uuid[];
    ords uuid[];
    n int; i int; aff uuid; aff_user uuid; ord uuid; cv uuid;
    amt_paid bigint; amt_other bigint; st text; paid_sum bigint; earn_sum bigint;
BEGIN
    affs := ARRAY(SELECT a.id FROM affiliate a JOIN users u ON u.id = a.user_id
                  WHERE u.role = 'USER' ORDER BY a.id LIMIT 8);
    ords := ARRAY(SELECT id FROM customer_order
                  WHERE id NOT IN (SELECT order_id FROM affiliate_conversion) ORDER BY id LIMIT 16);
    n := LEAST(COALESCE(array_length(affs,1),0), COALESCE(array_length(ords,1),0) / 2);
    FOR i IN 1..n LOOP
        aff := affs[i];
        SELECT user_id INTO aff_user FROM affiliate WHERE id = aff;
        amt_paid := 2000 + (i * 400);
        amt_other := 900 + (i * 150);
        st := CASE WHEN i % 2 = 0 THEN 'APPROVED' ELSE 'PENDING' END;

        -- conversión + comisión PAID (liquidada)
        ord := ords[2*i-1]; cv := gen_random_uuid();
        INSERT INTO affiliate_conversion(id, affiliate_id, referred_user_id, order_id, base_amount_cents, currency, status, created_at, updated_at)
        VALUES (cv, aff, NULL, ord, amt_paid*10, 'EUR', 'CONFIRMED', now() - interval '20 days', now());
        INSERT INTO affiliate_commission(id, affiliate_id, conversion_id, amount_cents, currency, percentage, status, approved_at, paid_at, created_at, updated_at, note)
        VALUES (gen_random_uuid(), aff, cv, amt_paid, 'EUR', 10.0, 'PAID', now() - interval '6 days', now() - interval '2 days', now() - interval '20 days', now(), 'Demo: 10%');

        -- conversión + comisión APPROVED o PENDING
        ord := ords[2*i]; cv := gen_random_uuid();
        INSERT INTO affiliate_conversion(id, affiliate_id, referred_user_id, order_id, base_amount_cents, currency, status, created_at, updated_at)
        VALUES (cv, aff, NULL, ord, amt_other*10, 'EUR', 'CONFIRMED', now() - interval '3 days', now());
        INSERT INTO affiliate_commission(id, affiliate_id, conversion_id, amount_cents, currency, percentage, status, approved_at, created_at, updated_at, note)
        VALUES (gen_random_uuid(), aff, cv, amt_other, 'EUR', 10.0, st,
                CASE WHEN st = 'APPROVED' THEN now() - interval '1 day' ELSE NULL END,
                now() - interval '3 days', now(), 'Demo: 10%');

        earn_sum := amt_paid + amt_other;
        paid_sum := amt_paid;
        UPDATE affiliate SET referrals_count = 2, earnings_usd_cents = earn_sum, payout_usd_cents = paid_sum
        WHERE id = aff;
    END LOOP;
END $$;
