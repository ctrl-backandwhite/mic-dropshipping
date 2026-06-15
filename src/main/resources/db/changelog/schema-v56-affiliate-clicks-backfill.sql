--liquibase formatted sql

--changeset nexadrop:v56-affiliate-clicks-backfill
-- DROP-694: reconcile the per-code click counter with the real attribution event log. Each counted
-- referral click writes one affiliate_attribution row, so the click count of a code can never be less
-- than its number of attributions. Legacy/seed data left clicks=0 while attributions (and the
-- conversions/commissions derived from them) existed, producing the "0 clicks but paid commissions"
-- inconsistency. Backfill clicks = GREATEST(clicks, attribution_count) so the stored data is coherent.
UPDATE affiliate_referral_code rc
SET clicks = GREATEST(rc.clicks, (
    SELECT COUNT(*) FROM affiliate_attribution a WHERE a.referral_code_id = rc.id
))
WHERE EXISTS (SELECT 1 FROM affiliate_attribution a WHERE a.referral_code_id = rc.id);
