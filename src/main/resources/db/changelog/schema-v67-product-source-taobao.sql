--liquibase formatted sql

--changeset nexadrop:v67-001 splitStatements:true endDelimiter:;
--comment: Todos los productos cargados hasta ahora son realmente de Taobao (estaban marcados como '1688'). Normaliza solo los existentes; los futuros se importan con su origen real.
UPDATE product SET source = 'taobao' WHERE source = '1688';
