--liquibase formatted sql
--changeset nexadrop:v37-mentor-latin-names splitStatements:false endDelimiter:;
-- DROP-642 (A): algunos perfiles de mentor mostraban el nombre en caracteres chinos
-- (CJK) en Admin > Mentores y en el storefront. El display name del mentor procede de
-- users.display_name (MentorProfileDtoMapper lo expone verbatim); los usuarios demo
-- CN/HK/SG se sembraron con nombre chino y algunos de ellos tienen perfil de mentor.
--
-- El seed (DemoOperationsSeedRunner.seedCustomers) ya se corrigió para usar pinyin
-- latino en futuros arranques. Aquí transliteramos a latino los usuarios CON perfil de
-- mentor cuyo display_name es CJK. Es idempotente: tras correr, ya no quedan nombres
-- CJK en mentores y los UPDATE no re-coinciden.
UPDATE users SET display_name = 'Ming Li'
 WHERE display_name = '明 李'
   AND id IN (SELECT user_id FROM mentor_profile);

UPDATE users SET display_name = 'Fang Wang'
 WHERE display_name = '芳 王'
   AND id IN (SELECT user_id FROM mentor_profile);

UPDATE users SET display_name = 'Wei Zhang'
 WHERE display_name = '伟 张'
   AND id IN (SELECT user_id FROM mentor_profile);

UPDATE users SET display_name = 'Fang Liu'
 WHERE display_name = '芳 刘'
   AND id IN (SELECT user_id FROM mentor_profile);

UPDATE users SET display_name = 'Min Chen'
 WHERE display_name = '敏 陈'
   AND id IN (SELECT user_id FROM mentor_profile);

UPDATE users SET display_name = 'Na Yang'
 WHERE display_name = '娜 杨'
   AND id IN (SELECT user_id FROM mentor_profile);
