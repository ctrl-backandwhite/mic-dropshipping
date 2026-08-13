--liquibase formatted sql

--changeset nexa:v113-backfill-free-trial-used
-- La prueba gratis es de un solo uso por cuenta (users.free_trial_used). Algunas cuentas antiguas tienen
-- una suscripción FREE pero la marca quedó a false (se creó por una vía que no la fijaba), lo que permitiría
-- reactivar la prueba. Se rellena la marca para toda cuenta que ya tenga (o haya tenido) un plan FREE.
UPDATE users u SET free_trial_used = true
WHERE u.free_trial_used = false
  AND EXISTS (
    SELECT 1 FROM customer_subscription cs
    JOIN subscription_plan sp ON sp.id = cs.plan_id
    WHERE cs.user_id = u.id AND upper(sp.code) = 'FREE'
  );
