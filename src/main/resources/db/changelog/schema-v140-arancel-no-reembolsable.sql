--liquibase formatted sql

--changeset nexadrop:v140-arancel-no-reembolsable splitStatements:false
--
-- Las condiciones no decían qué pasa con el arancel de importación cuando un pedido se devuelve.
--
-- Desde el 1-jul-2026 los envíos a la UE por debajo de 150 EUR pagan un arancel fijo por artículo, y el
-- transportista lo cobra al dar entrada al paquete en su almacén. Su contrato es explícito en que ese
-- importe NO se reintegra pase lo que pase después: entrega fallida, paquete rechazado o devolución
-- («无论基于何种原因导致包裹在海外派送失败、包裹被退回等情况，我司已代收的临时固定关税均不予退还»).
--
-- Hasta ahora eso no estaba escrito en ninguna parte, así que el cliente no podía saberlo y el coste se
-- absorbía en silencio.
--
-- Criterio de redacción: se distingue el DESISTIMIENTO del resto de devoluciones. En un desistimiento
-- de consumidor, el artículo 13 de la Directiva 2011/83/UE obliga a reembolsar todos los pagos
-- recibidos, incluidos los gastos de entrega estándar; retener ahí el arancel sería probablemente una
-- cláusula abusiva, así que lo asume el comercio. Cuando la devolución nace de una causa imputable al
-- cliente —rechaza el paquete, da una dirección incorrecta o la entrega falla por su parte— el arancel
-- sí se descuenta, porque ya está pagado y no se recupera.
--
--
-- IMPORTANTE: el texto se deja como BORRADOR (`draft_body`), no como texto vivo.
--
-- Publicar no es solo cambiar el cuerpo: `LegalDocumentService.publicar()` sube la versión de TODOS los
-- documentos y avisa a cada usuario de que hay condiciones nuevas. Escribir directo en `body` cambiaría
-- el texto en silencio, dejando a la gente con `terms_accepted_version` apuntando a una versión que ya
-- no dice lo mismo — exactamente lo que ese servicio se preocupa de impedir.
--
-- Así que esto deja el texto preparado y es una persona quien lo revisa y pulsa publicar desde el admin,
-- que es cuando sale el aviso. Si ya hubiera un borrador en curso, se respeta y se le añade encima
-- (COALESCE), en vez de pisarlo.
--
-- El texto se añade en los 8 idiomas de las condiciones (sección «Pedidos, envíos y aduanas», que ocupa
-- el mismo índice en todos) y en el documento de desistimiento, que solo existe en español e inglés.

-- ── Condiciones generales: sección «Pedidos, envíos y aduanas» ──────────────────────────────────────

UPDATE legal_document SET draft_body = jsonb_set(COALESCE(draft_body, body), '{sections,5,p}',
     (COALESCE(draft_body, body)->'sections'->5->'p') || to_jsonb(
 'En los envíos a la Unión Europea se liquida en aduana un arancel fijo por artículo. El transportista lo cobra al dar entrada al paquete y no lo reintegra después por ningún motivo. Si ejerces tu derecho de desistimiento, ese coste lo asumimos nosotros y no afecta a tu reembolso. Si la devolución se produce porque rechazas el paquete, porque la dirección facilitada es incorrecta o porque la entrega falla por causas que te sean imputables, el arancel se descuenta del importe a devolver.'::text))
 WHERE doc_type = 'terms' AND lang = 'es';

UPDATE legal_document SET draft_body = jsonb_set(COALESCE(draft_body, body), '{sections,5,p}',
     (COALESCE(draft_body, body)->'sections'->5->'p') || to_jsonb(
 'Shipments to the European Union are charged a fixed per-item customs duty. The carrier collects it when the parcel is checked in and does not refund it afterwards for any reason. If you exercise your right of withdrawal, we absorb that cost and your refund is unaffected. If the return happens because you refuse the parcel, because the address you provided is incorrect, or because delivery fails for reasons attributable to you, the duty is deducted from the amount refunded.'::text))
 WHERE doc_type = 'terms' AND lang = 'en';

UPDATE legal_document SET draft_body = jsonb_set(COALESCE(draft_body, body), '{sections,5,p}',
     (COALESCE(draft_body, body)->'sections'->5->'p') || to_jsonb(
 'Bei Sendungen in die Europäische Union wird ein fester Zoll pro Artikel erhoben. Der Transporteur zieht ihn bei der Annahme des Pakets ein und erstattet ihn danach aus keinem Grund zurück. Wenn Sie Ihr Widerrufsrecht ausüben, übernehmen wir diese Kosten und Ihre Rückerstattung bleibt unberührt. Erfolgt die Rücksendung, weil Sie das Paket verweigern, weil die angegebene Adresse falsch ist oder weil die Zustellung aus von Ihnen zu vertretenden Gründen scheitert, wird der Zoll vom Erstattungsbetrag abgezogen.'::text))
 WHERE doc_type = 'terms' AND lang = 'de';

UPDATE legal_document SET draft_body = jsonb_set(COALESCE(draft_body, body), '{sections,5,p}',
     (COALESCE(draft_body, body)->'sections'->5->'p') || to_jsonb(
 'Les envois vers l''Union européenne sont soumis à un droit de douane fixe par article. Le transporteur le perçoit à la prise en charge du colis et ne le rembourse ensuite pour aucun motif. Si vous exercez votre droit de rétractation, nous prenons ce coût à notre charge et votre remboursement n''est pas affecté. Si le retour intervient parce que vous refusez le colis, parce que l''adresse fournie est incorrecte ou parce que la livraison échoue pour des raisons qui vous sont imputables, ce droit est déduit du montant remboursé.'::text))
 WHERE doc_type = 'terms' AND lang = 'fr';

UPDATE legal_document SET draft_body = jsonb_set(COALESCE(draft_body, body), '{sections,5,p}',
     (COALESCE(draft_body, body)->'sections'->5->'p') || to_jsonb(
 'Le spedizioni verso l''Unione Europea sono soggette a un dazio fisso per articolo. Il vettore lo incassa al momento della presa in carico del pacco e non lo rimborsa in seguito per alcun motivo. Se eserciti il diritto di recesso, ci facciamo carico noi di quel costo e il tuo rimborso non ne risente. Se la restituzione avviene perché rifiuti il pacco, perché l''indirizzo fornito è errato o perché la consegna fallisce per cause a te imputabili, il dazio viene detratto dall''importo da rimborsare.'::text))
 WHERE doc_type = 'terms' AND lang = 'it';

UPDATE legal_document SET draft_body = jsonb_set(COALESCE(draft_body, body), '{sections,5,p}',
     (COALESCE(draft_body, body)->'sections'->5->'p') || to_jsonb(
 'Voor zendingen naar de Europese Unie geldt een vast invoerrecht per artikel. De vervoerder int dit bij inname van het pakket en betaalt het daarna om geen enkele reden terug. Als u uw herroepingsrecht uitoefent, nemen wij die kosten voor onze rekening en verandert er niets aan uw terugbetaling. Vindt de retour plaats doordat u het pakket weigert, doordat het opgegeven adres onjuist is of doordat de bezorging mislukt door oorzaken die aan u toe te rekenen zijn, dan wordt het invoerrecht in mindering gebracht op het terug te betalen bedrag.'::text))
 WHERE doc_type = 'terms' AND lang = 'nl';

UPDATE legal_document SET draft_body = jsonb_set(COALESCE(draft_body, body), '{sections,5,p}',
     (COALESCE(draft_body, body)->'sections'->5->'p') || to_jsonb(
 'Os envios para a União Europeia estão sujeitos a um direito aduaneiro fixo por artigo. A transportadora cobra-o à entrada da encomenda e não o devolve depois por motivo algum. Se exerceres o teu direito de livre resolução, esse custo é assumido por nós e o teu reembolso não é afetado. Se a devolução ocorrer porque recusas a encomenda, porque a morada indicada está incorreta ou porque a entrega falha por causas que te sejam imputáveis, o direito aduaneiro é descontado do valor a reembolsar.'::text))
 WHERE doc_type = 'terms' AND lang = 'pt';

UPDATE legal_document SET draft_body = jsonb_set(COALESCE(draft_body, body), '{sections,5,p}',
     (COALESCE(draft_body, body)->'sections'->5->'p') || to_jsonb(
 '寄往欧盟的包裹须按件缴纳固定关税。承运商在包裹签入时收取，此后无论何种原因均不退还。若您行使退货权（撤回权），该费用由我方承担，不影响您的退款。若因您拒收包裹、提供的地址有误，或因可归责于您的原因导致派送失败而产生退货，该关税将从退款金额中扣除。'::text))
 WHERE doc_type = 'terms' AND lang = 'zh';

-- ── Desistimiento: sección «Cuándo recuperas tu dinero» (índice 3, base 0) ──────────────────────────

UPDATE legal_document SET draft_body = jsonb_set(COALESCE(draft_body, body), '{sections,3,p}',
     (COALESCE(draft_body, body)->'sections'->3->'p') || to_jsonb(
 'Un matiz sobre el arancel de aduanas en los envíos a la Unión Europea: el transportista lo cobra al dar entrada al paquete y no lo reintegra después. Si desistes, ese coste lo asumimos nosotros y recuperas todo lo que pagaste. Solo se descuenta cuando la devolución nace de una causa que te es imputable —rechazas el paquete, la dirección facilitada es incorrecta o la entrega falla por tu parte—, porque en esos casos el arancel ya está pagado y no se recupera.'::text))
 WHERE doc_type = 'withdrawal' AND lang = 'es';

UPDATE legal_document SET draft_body = jsonb_set(COALESCE(draft_body, body), '{sections,3,p}',
     (COALESCE(draft_body, body)->'sections'->3->'p') || to_jsonb(
 'One note about customs duty on shipments to the European Union: the carrier collects it when the parcel is checked in and does not refund it afterwards. If you withdraw, we absorb that cost and you get back everything you paid. It is only deducted when the return stems from a cause attributable to you — you refuse the parcel, the address you provided is incorrect, or delivery fails on your side — because in those cases the duty is already paid and cannot be recovered.'::text))
 WHERE doc_type = 'withdrawal' AND lang = 'en';
