--liquibase formatted sql
--changeset nexadrop:v33-billing-period-normalize
-- DROP-634: el periodo de las suscripciones se sembró en dos convenciones
-- históricas ("MONTH"/"YEAR" desde el seed, "MONTHLY"/"YEARLY" desde el use case
-- real), lo que provocaba que la tabla de admin mezclara etiquetas traducidas con
-- el valor crudo del enum. Normalizamos todas las filas al formato canónico
-- MONTHLY/YEARLY para que el frontend siempre las traduzca vía i18n.
UPDATE customer_subscription SET billing_period = 'MONTHLY' WHERE upper(billing_period) IN ('MONTH', 'MONTHLY');
UPDATE customer_subscription SET billing_period = 'YEARLY'  WHERE upper(billing_period) IN ('YEAR', 'YEARLY');

--changeset nexadrop:v33-billing-free-not-trial
-- DROP-634: un plan gratuito (FREE) no debe figurar en periodo de prueba — un
-- plan sin coste está activo desde el primer momento. Promovemos a ACTIVE toda
-- suscripción TRIALING cuyo plan no tenga precio (free) y limpiamos trial_ends_at.
UPDATE customer_subscription cs
SET status = 'ACTIVE', trial_ends_at = NULL
WHERE cs.status = 'TRIALING'
  AND cs.plan_id IN (
    SELECT sp.id FROM subscription_plan sp
    WHERE (sp.price_monthly_cents = 0 AND sp.price_yearly_cents = 0)
       OR upper(sp.code) = 'FREE'
  );
