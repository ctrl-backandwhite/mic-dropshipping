--liquibase formatted sql

--changeset nexadrop:v8d-partner-001 splitStatements:true endDelimiter:;
--comment: DROP-128 — set human-readable client_name on existing OAuth2 clients (some legacy rows have the random UUID stored as client_name).

UPDATE oauth2_registered_client
SET client_name = 'NX036 Admin SPA'
WHERE client_id = 'admin-spa';

UPDATE oauth2_registered_client
SET client_name = 'NX036 Storefront SPA'
WHERE client_id = 'storefront-spa';

UPDATE oauth2_registered_client
SET client_name = 'Demo Partner (server-to-server)'
WHERE client_id = 'demo-partner';
