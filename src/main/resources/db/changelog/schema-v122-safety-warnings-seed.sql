--liquibase formatted sql

-- Advertencias de seguridad del art. 19.d del Reglamento (UE) 2023/988 para las familias del catálogo que
-- presentan un riesgo real. Se declaran en la categoría PADRE y se heredan hacia los hijos, así que
-- "Belleza · dispositivos" cubre secador, rizador, cortapelos e irrigador dental sin repetir nada.
--
-- No se siembra nada para la ropa y el calzado corrientes: no tienen advertencia obligatoria y llenarlos de
-- avisos genéricos solo lograría que el comprador dejara de leerlos.

-- splitStatements:false es imprescindible: el cuerpo plpgsql lleva punto y coma dentro y Liquibase lo
-- trocearía a mitad de la función. runOnChange permite corregir la redacción de una advertencia sin
-- inventar un changeset nuevo.
--changeset nexa:v122-safety-warnings-seed runOnChange:true splitStatements:false
CREATE OR REPLACE FUNCTION pg_temp.sembrar_advertencia(p_slug text, p_code text, p_pos int, p_textos text[])
RETURNS void AS $fn$
DECLARE
    v_categoria uuid;
    v_warning   uuid;
    v_idiomas   text[] := ARRAY['es', 'en', 'pt', 'zh', 'fr', 'de', 'it', 'nl'];
    i           int;
BEGIN
    SELECT id INTO v_categoria FROM category WHERE slug = p_slug;
    -- La taxonomía difiere entre entornos: si la categoría no está, se omite en silencio en vez de abortar
    -- el arranque entero por una advertencia que allí no aplica.
    IF v_categoria IS NULL THEN
        RETURN;
    END IF;

    SELECT id INTO v_warning FROM category_safety_warning
     WHERE category_id = v_categoria AND code = p_code;

    IF v_warning IS NULL THEN
        v_warning := gen_random_uuid();
        INSERT INTO category_safety_warning (id, category_id, code, position, active, created_by, updated_by)
        VALUES (v_warning, v_categoria, p_code, p_pos, true, 'v122-seed', 'v122-seed');
    ELSE
        UPDATE category_safety_warning SET position = p_pos, updated_at = now(), updated_by = 'v122-seed'
         WHERE id = v_warning;
    END IF;

    FOR i IN 1 .. array_length(v_idiomas, 1) LOOP
        INSERT INTO category_safety_warning_translation (id, warning_id, language, text)
        VALUES (gen_random_uuid(), v_warning, v_idiomas[i], p_textos[i])
        ON CONFLICT (warning_id, language) DO UPDATE SET text = EXCLUDED.text, updated_at = now();
    END LOOP;
END;
$fn$ LANGUAGE plpgsql;

-- ── Aparatos eléctricos: dispositivos de belleza y pequeño electrodoméstico ──────────────────────────
-- Caen además bajo la Directiva 2014/35/UE (baja tensión), 2014/30/UE (compatibilidad electromagnética) y
-- 2011/65/UE (RoHS), las tres en la lista cerrada del art. 4.5 del Reglamento (UE) 2019/1020.
SELECT pg_temp.sembrar_advertencia('belleza-dispositivos-fragancia', 'ELECTRICAL_SAFETY', 10, ARRAY[
    'Aparato eléctrico: leer las instrucciones antes de usarlo. No sumergir en agua ni manipular con las manos mojadas. Desconectar de la corriente después de cada uso.',
    'Electrical appliance: read the instructions before use. Do not immerse in water or handle with wet hands. Unplug after every use.',
    'Aparelho elétrico: leia as instruções antes de usar. Não mergulhar em água nem manusear com as mãos molhadas. Desligue da corrente após cada utilização.',
    '电器产品：使用前请阅读说明书。请勿浸入水中或用湿手操作。每次使用后请拔下电源。',
    'Appareil électrique : lire la notice avant utilisation. Ne pas immerger dans l''eau ni manipuler avec les mains mouillées. Débrancher après chaque utilisation.',
    'Elektrogerät: Vor Gebrauch die Anleitung lesen. Nicht in Wasser tauchen und nicht mit nassen Händen bedienen. Nach jedem Gebrauch vom Netz trennen.',
    'Apparecchio elettrico: leggere le istruzioni prima dell''uso. Non immergere in acqua né maneggiare con le mani bagnate. Scollegare dalla rete dopo ogni utilizzo.',
    'Elektrisch apparaat: lees de instructies vóór gebruik. Niet onderdompelen in water en niet met natte handen bedienen. Haal na elk gebruik de stekker uit het stopcontact.']);

SELECT pg_temp.sembrar_advertencia('belleza-dispositivos-fragancia', 'ADULT_SUPERVISION', 20, ARRAY[
    'No apto para uso por menores sin supervisión de un adulto. Mantener fuera del alcance de los niños.',
    'Not suitable for use by children without adult supervision. Keep out of the reach of children.',
    'Não adequado para uso por menores sem supervisão de um adulto. Manter fora do alcance das crianças.',
    '未成年人须在成人监护下使用。请放置在儿童无法触及之处。',
    'Ne convient pas à une utilisation par des enfants sans surveillance d''un adulte. Tenir hors de portée des enfants.',
    'Nicht zur Verwendung durch Kinder ohne Aufsicht eines Erwachsenen geeignet. Außerhalb der Reichweite von Kindern aufbewahren.',
    'Non adatto all''uso da parte di minori senza la supervisione di un adulto. Tenere fuori dalla portata dei bambini.',
    'Niet geschikt voor gebruik door kinderen zonder toezicht van een volwassene. Buiten het bereik van kinderen houden.']);

SELECT pg_temp.sembrar_advertencia('hogar-electrodomesticos-smart', 'ELECTRICAL_SAFETY', 10, ARRAY[
    'Aparato eléctrico: leer las instrucciones antes de usarlo. No sumergir en agua ni manipular con las manos mojadas. Desconectar de la corriente después de cada uso.',
    'Electrical appliance: read the instructions before use. Do not immerse in water or handle with wet hands. Unplug after every use.',
    'Aparelho elétrico: leia as instruções antes de usar. Não mergulhar em água nem manusear com as mãos molhadas. Desligue da corrente após cada utilização.',
    '电器产品：使用前请阅读说明书。请勿浸入水中或用湿手操作。每次使用后请拔下电源。',
    'Appareil électrique : lire la notice avant utilisation. Ne pas immerger dans l''eau ni manipuler avec les mains mouillées. Débrancher après chaque utilisation.',
    'Elektrogerät: Vor Gebrauch die Anleitung lesen. Nicht in Wasser tauchen und nicht mit nassen Händen bedienen. Nach jedem Gebrauch vom Netz trennen.',
    'Apparecchio elettrico: leggere le istruzioni prima dell''uso. Non immergere in acqua né maneggiare con le mani bagnate. Scollegare dalla rete dopo ogni utilizzo.',
    'Elektrisch apparaat: lees de instructies vóór gebruik. Niet onderdompelen in water en niet met natte handen bedienen. Haal na elk gebruik de stekker uit het stopcontact.']);

-- Enchufe: la mercancía viene de China y el tipo de clavija no siempre es el europeo.
SELECT pg_temp.sembrar_advertencia('hogar-electrodomesticos-smart', 'PLUG_TYPE', 30, ARRAY[
    'Comprobar el tipo de enchufe y la tensión antes de usarlo. Si el conector no corresponde al de tu país, utiliza un adaptador homologado; nunca fuerces la clavija en la toma.',
    'Check the plug type and voltage before use. If the connector does not match your country''s, use a certified adapter; never force the plug into the socket.',
    'Verifique o tipo de ficha e a tensão antes de usar. Se o conector não corresponder ao do seu país, utilize um adaptador homologado; nunca force a ficha na tomada.',
    '使用前请检查插头类型和电压。若插头与您所在国家的插座不符，请使用合格的转换器；切勿强行插入。',
    'Vérifier le type de prise et la tension avant utilisation. Si le connecteur ne correspond pas à celui de votre pays, utilisez un adaptateur homologué ; ne forcez jamais la fiche dans la prise.',
    'Steckertyp und Spannung vor Gebrauch prüfen. Passt der Stecker nicht zu Ihrem Land, verwenden Sie einen zugelassenen Adapter; den Stecker niemals gewaltsam in die Steckdose stecken.',
    'Verificare il tipo di spina e la tensione prima dell''uso. Se il connettore non corrisponde a quello del tuo Paese, utilizza un adattatore omologato; non forzare mai la spina nella presa.',
    'Controleer vóór gebruik het stekkertype en de spanning. Past de stekker niet bij die van uw land, gebruik dan een goedgekeurde adapter; duw de stekker nooit met kracht in het stopcontact.']);

-- ── Ropa infantil y de bebé ─────────────────────────────────────────────────────────────────────────
-- Los cordones y las piezas pequeñas son las dos causas típicas de retirada del mercado en esta familia.
SELECT pg_temp.sembrar_advertencia('moda-ropa-infantil', 'CHOKING_SMALL_PARTS', 10, ARRAY[
    'Puede contener piezas pequeñas (botones, lazos, apliques) que se desprendan: riesgo de asfixia. Revisar la prenda antes de cada uso y no dejar al niño sin vigilancia con ella.',
    'May contain small parts (buttons, bows, appliqués) that can come loose: choking hazard. Check the garment before each use and do not leave a child unattended with it.',
    'Pode conter peças pequenas (botões, laços, apliques) que se soltem: risco de asfixia. Verifique a peça antes de cada utilização e não deixe a criança sem vigilância com ela.',
    '可能含有会脱落的小部件（纽扣、蝴蝶结、贴花）：有窒息风险。每次使用前请检查衣物，请勿让儿童在无人看管下穿着。',
    'Peut contenir de petits éléments (boutons, nœuds, applications) susceptibles de se détacher : risque d''étouffement. Vérifier le vêtement avant chaque utilisation et ne pas laisser l''enfant sans surveillance.',
    'Kann Kleinteile enthalten (Knöpfe, Schleifen, Applikationen), die sich lösen können: Erstickungsgefahr. Das Kleidungsstück vor jedem Gebrauch prüfen und das Kind damit nicht unbeaufsichtigt lassen.',
    'Può contenere piccole parti (bottoni, fiocchi, applicazioni) che possono staccarsi: rischio di soffocamento. Controllare il capo prima di ogni uso e non lasciare il bambino incustodito.',
    'Kan kleine onderdelen bevatten (knopen, strikken, applicaties) die kunnen loskomen: verstikkingsgevaar. Controleer het kledingstuk vóór elk gebruik en laat een kind er niet zonder toezicht mee achter.']);

SELECT pg_temp.sembrar_advertencia('moda-ropa-infantil', 'CHILD_CORD_HAZARD', 20, ARRAY[
    'Los cordones, cintas y capuchas pueden engancharse y provocar estrangulamiento. No usar en parques infantiles ni durante el sueño.',
    'Cords, ties and hoods can snag and cause strangulation. Do not use on playgrounds or during sleep.',
    'Os cordões, fitas e capuzes podem prender-se e provocar estrangulamento. Não usar em parques infantis nem durante o sono.',
    '拉绳、系带和帽子可能被勾住并造成勒颈危险。请勿在游乐场或睡眠时穿着。',
    'Les cordons, liens et capuches peuvent s''accrocher et provoquer une strangulation. Ne pas utiliser dans les aires de jeux ni pendant le sommeil.',
    'Kordeln, Bänder und Kapuzen können sich verfangen und zu Strangulation führen. Nicht auf Spielplätzen oder im Schlaf verwenden.',
    'Cordoncini, lacci e cappucci possono impigliarsi e causare strangolamento. Non utilizzare nei parchi giochi né durante il sonno.',
    'Koorden, linten en capuchons kunnen blijven haken en wurging veroorzaken. Niet gebruiken op speelplaatsen of tijdens het slapen.']);

SELECT pg_temp.sembrar_advertencia('bebe-ropa-textiles-regalos', 'CHOKING_SMALL_PARTS', 10, ARRAY[
    'Puede contener piezas pequeñas que se desprendan: riesgo de asfixia. Revisar antes de cada uso y no dejar al bebé sin vigilancia.',
    'May contain small parts that can come loose: choking hazard. Check before each use and do not leave the baby unattended.',
    'Pode conter peças pequenas que se soltem: risco de asfixia. Verifique antes de cada utilização e não deixe o bebé sem vigilância.',
    '可能含有会脱落的小部件：有窒息风险。每次使用前请检查，请勿让婴儿在无人看管下使用。',
    'Peut contenir de petits éléments susceptibles de se détacher : risque d''étouffement. Vérifier avant chaque utilisation et ne pas laisser le bébé sans surveillance.',
    'Kann Kleinteile enthalten, die sich lösen können: Erstickungsgefahr. Vor jedem Gebrauch prüfen und das Baby nicht unbeaufsichtigt lassen.',
    'Può contenere piccole parti che possono staccarsi: rischio di soffocamento. Controllare prima di ogni uso e non lasciare il bambino incustodito.',
    'Kan kleine onderdelen bevatten die kunnen loskomen: verstikkingsgevaar. Controleer vóór elk gebruik en laat de baby niet zonder toezicht.']);

-- ── Gafas de sol: son EPI (Reglamento (UE) 2016/425), en la lista del art. 4.5 del 2019/1020 ─────────
SELECT pg_temp.sembrar_advertencia('moda-acc-06', 'SUNGLASSES_USE', 10, ARRAY[
    'No mirar directamente al sol, ni siquiera con estas gafas. No aptas para observar eclipses ni para la conducción nocturna.',
    'Do not look directly at the sun, even while wearing these glasses. Not suitable for viewing eclipses or for night driving.',
    'Não olhe diretamente para o sol, mesmo com estes óculos. Não adequados para observar eclipses nem para conduzir à noite.',
    '即使佩戴本眼镜也请勿直视太阳。不适用于观察日食或夜间驾驶。',
    'Ne pas regarder directement le soleil, même avec ces lunettes. Ne conviennent pas à l''observation des éclipses ni à la conduite de nuit.',
    'Auch mit dieser Brille nicht direkt in die Sonne blicken. Nicht zur Beobachtung von Sonnenfinsternissen oder für Nachtfahrten geeignet.',
    'Non guardare direttamente il sole, nemmeno con questi occhiali. Non adatti all''osservazione di eclissi né alla guida notturna.',
    'Kijk niet rechtstreeks in de zon, ook niet met deze bril op. Niet geschikt om zonsverduisteringen te bekijken of voor autorijden in het donker.']);

-- ── Relojes: pila de botón. La ingestión por un niño causa lesiones internas graves en pocas horas ───
SELECT pg_temp.sembrar_advertencia('moda-acc-08', 'BUTTON_BATTERY', 10, ARRAY[
    'Contiene una pila de botón. Su ingestión provoca quemaduras internas graves en pocas horas y puede ser mortal. Mantener fuera del alcance de los niños y acudir de inmediato a urgencias si se sospecha ingestión.',
    'Contains a button battery. Swallowing it causes severe internal burns within hours and can be fatal. Keep out of the reach of children and seek emergency care immediately if ingestion is suspected.',
    'Contém uma pilha de botão. A ingestão provoca queimaduras internas graves em poucas horas e pode ser fatal. Manter fora do alcance das crianças e procurar urgências de imediato em caso de suspeita de ingestão.',
    '内含纽扣电池。误吞会在数小时内造成严重内部灼伤，可能致命。请放置在儿童无法触及之处，如怀疑误吞请立即就医。',
    'Contient une pile bouton. Son ingestion provoque de graves brûlures internes en quelques heures et peut être mortelle. Tenir hors de portée des enfants et consulter immédiatement les urgences en cas de suspicion d''ingestion.',
    'Enthält eine Knopfzelle. Das Verschlucken verursacht innerhalb weniger Stunden schwere innere Verätzungen und kann tödlich sein. Außerhalb der Reichweite von Kindern aufbewahren und bei Verdacht auf Verschlucken sofort den Notdienst aufsuchen.',
    'Contiene una pila a bottone. L''ingestione provoca gravi ustioni interne in poche ore e può essere mortale. Tenere fuori dalla portata dei bambini e rivolgersi immediatamente al pronto soccorso in caso di sospetta ingestione.',
    'Bevat een knoopcelbatterij. Inslikken veroorzaakt binnen enkele uren ernstige inwendige brandwonden en kan dodelijk zijn. Buiten het bereik van kinderen houden en bij vermoeden van inslikken onmiddellijk medische hulp inschakelen.']);

SELECT pg_temp.sembrar_advertencia('moda-acc-21', 'BUTTON_BATTERY', 10, ARRAY[
    'Contiene una pila de botón. Su ingestión provoca quemaduras internas graves en pocas horas y puede ser mortal. Mantener fuera del alcance de los niños y acudir de inmediato a urgencias si se sospecha ingestión.',
    'Contains a button battery. Swallowing it causes severe internal burns within hours and can be fatal. Keep out of the reach of children and seek emergency care immediately if ingestion is suspected.',
    'Contém uma pilha de botão. A ingestão provoca queimaduras internas graves em poucas horas e pode ser fatal. Manter fora do alcance das crianças e procurar urgências de imediato em caso de suspeita de ingestão.',
    '内含纽扣电池。误吞会在数小时内造成严重内部灼伤，可能致命。请放置在儿童无法触及之处，如怀疑误吞请立即就医。',
    'Contient une pile bouton. Son ingestion provoque de graves brûlures internes en quelques heures et peut être mortelle. Tenir hors de portée des enfants et consulter immédiatement les urgences en cas de suspicion d''ingestion.',
    'Enthält eine Knopfzelle. Das Verschlucken verursacht innerhalb weniger Stunden schwere innere Verätzungen und kann tödlich sein. Außerhalb der Reichweite von Kindern aufbewahren und bei Verdacht auf Verschlucken sofort den Notdienst aufsuchen.',
    'Contiene una pila a bottone. L''ingestione provoca gravi ustioni interne in poche ore e può essere mortale. Tenere fuori dalla portata dei bambini e rivolgersi immediatamente al pronto soccorso in caso di sospetta ingestione.',
    'Bevat een knoopcelbatterij. Inslikken veroorzaakt binnen enkele uren ernstige inwendige brandwonden en kan dodelijk zijn. Buiten het bereik van kinderen houden en bij vermoeden van inslikken onmiddellijk medische hulp inschakelen.']);

-- ── Bisutería: níquel. Restringido por el anexo XVII del Reglamento REACH (entrada 27) ───────────────
DO $seed$
DECLARE
    v_slug text;
    v_joyeria text[] := ARRAY['moda-acc-02', 'moda-acc-03', 'moda-acc-04', 'moda-acc-07', 'moda-acc-11',
                              'moda-acc-17', 'moda-acc-19'];
BEGIN
    FOREACH v_slug IN ARRAY v_joyeria LOOP
        PERFORM pg_temp.sembrar_advertencia(v_slug, 'NICKEL_ALLERGY', 10, ARRAY[
            'Bisutería: puede contener níquel y provocar reacciones alérgicas en pieles sensibles. Retirar la pieza si aparece enrojecimiento o picor. No apta para menores de 3 años.',
            'Costume jewellery: may contain nickel and cause allergic reactions on sensitive skin. Remove the item if redness or itching appears. Not suitable for children under 3.',
            'Bijutaria: pode conter níquel e provocar reações alérgicas em peles sensíveis. Retire a peça se surgir vermelhidão ou comichão. Não adequado a menores de 3 anos.',
            '仿真首饰：可能含镍，敏感肌肤可能出现过敏反应。如出现发红或瘙痒请立即取下。不适合3岁以下儿童。',
            'Bijoux fantaisie : peuvent contenir du nickel et provoquer des réactions allergiques sur les peaux sensibles. Retirer le bijou en cas de rougeur ou de démangeaison. Ne convient pas aux enfants de moins de 3 ans.',
            'Modeschmuck: kann Nickel enthalten und bei empfindlicher Haut allergische Reaktionen auslösen. Bei Rötung oder Juckreiz das Schmuckstück abnehmen. Nicht für Kinder unter 3 Jahren geeignet.',
            'Bigiotteria: può contenere nichel e provocare reazioni allergiche su pelli sensibili. Rimuovere l''articolo in caso di arrossamento o prurito. Non adatto ai minori di 3 anni.',
            'Modesieraden: kunnen nikkel bevatten en allergische reacties veroorzaken bij een gevoelige huid. Verwijder het sieraad bij roodheid of jeuk. Niet geschikt voor kinderen onder de 3 jaar.']);
    END LOOP;
END
$seed$;
