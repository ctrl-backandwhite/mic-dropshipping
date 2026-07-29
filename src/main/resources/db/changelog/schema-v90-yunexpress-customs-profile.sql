--liquibase formatted sql

--changeset nexadrop:v90-yunexpress-customs-profile splitStatements:false
-- Datos que YunExpress exige y que el catálogo no tenía. Referencia: «云途物流API接口开发规范 (OMS 1.2.5)».
--
--  · /api/Freight/GetPriceTrial (cotización): CountryCode + Weight(kg) OBLIGATORIOS, PackageType
--    OBLIGATORIO (0 = 普货 carga general, 1 = 带电 con batería) y Length/Width/Height en cm — que si
--    no se envían valen 1, con lo que el peso VOLUMÉTRICO sale 0 y se cotiza de menos: el carrier
--    repesa en almacén y factura la diferencia contra nuestro margen.
--  · /api/WayBill/CreateOrder → Parcels[]: EName(inglés), Quantity, UnitPrice, UnitWeight y
--    CurrencyCode obligatorios; HSCode, InvoicePart (材质, material) e InvoiceUsage (用途, uso) son
--    "no obligatorios" en la API pero son lo que la aduana de destino usa para clasificar y liquidar.
--
-- Se modela como PERFIL POR CATEGORÍA (no por producto): la categoría-hoja ya determina la partida
-- arancelaria, el material declarado, el uso y el formato de paquete. Así los productos que se carguen
-- después heredan el perfil sin trabajo manual, y corregir una partida es una fila, no 300 productos.
CREATE TABLE IF NOT EXISTS category_customs_profile (
    id              uuid PRIMARY KEY,
    category_slug   varchar(64)  NOT NULL UNIQUE,
    hs_code         varchar(12),                       -- partida SH a 6 dígitos (régimen H7 UE, envíos <=150 EUR)
    material        varchar(255),                      -- InvoicePart (材质) — en inglés, lo lee la aduana de destino
    usage_text      varchar(255),                      -- InvoiceUsage (用途) — en inglés
    battery_type    varchar(20) NOT NULL DEFAULT 'NONE', -- NONE | BUILT_IN | WITH_EQUIPMENT -> PackageType 0/1
    pack_length_mm  integer,                           -- dimensiones del PAQUETE (no del artículo) para el volumétrico
    pack_width_mm   integer,
    pack_height_mm  integer,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE category_customs_profile IS
 'Perfil aduanero y de embalaje por categoría-hoja: HS code, material y uso declarados, tipo de batería '
 '(PackageType de YunExpress) y dimensiones del paquete para el peso volumétrico.';

-- Campos aduaneros a nivel producto: se siembran desde el perfil de su categoría y se pueden afinar
-- producto a producto sin tocar el perfil (un abrigo de cuero dentro de una categoría textil, p. ej.).
ALTER TABLE product ADD COLUMN IF NOT EXISTS customs_material varchar(255);
ALTER TABLE product ADD COLUMN IF NOT EXISTS customs_usage    varchar(255);
ALTER TABLE product ADD COLUMN IF NOT EXISTS battery_type     varchar(20) NOT NULL DEFAULT 'NONE';

COMMENT ON COLUMN product.customs_material IS 'InvoicePart (材质) declarado a YunExpress, en inglés.';
COMMENT ON COLUMN product.customs_usage    IS 'InvoiceUsage (用途) declarado a YunExpress, en inglés.';
COMMENT ON COLUMN product.battery_type     IS 'NONE | BUILT_IN | WITH_EQUIPMENT. BUILT_IN/WITH_EQUIPMENT => PackageType=1 (带电).';

-- ── Perfiles por categoría-hoja ──────────────────────────────────────────────────────────────────
-- Las filas '<familia>-*' son el fallback de la familia para categorías sin perfil propio.
INSERT INTO category_customs_profile
 (id, category_slug, hs_code, material, usage_text, battery_type, pack_length_mm, pack_width_mm, pack_height_mm)
VALUES
 -- ── MUJER ──────────────────────────────────────────────────────────────────────────────────────
 (gen_random_uuid(),'moda-muj-*' ,'620443','Polyester woven fabric','Womens daily wear','NONE',320,240,50),
 (gen_random_uuid(),'moda-muj-01','620443','Polyester woven fabric','Womens dress, daily wear','NONE',320,240,50),
 (gen_random_uuid(),'moda-muj-02','610990','Knitted polyester and cotton','Womens t-shirt, daily wear','NONE',280,200,30),
 (gen_random_uuid(),'moda-muj-03','620423','Polyester woven fabric','Womens casual set, daily wear','NONE',340,260,70),
 (gen_random_uuid(),'moda-muj-04','620463','Polyester woven fabric','Womens trousers, daily wear','NONE',320,240,50),
 (gen_random_uuid(),'moda-muj-05','620453','Polyester woven fabric','Womens skirt, daily wear','NONE',300,220,40),
 (gen_random_uuid(),'moda-muj-06','620640','Polyester woven fabric','Womens blouse, daily wear','NONE',300,220,40),
 (gen_random_uuid(),'moda-muj-07','620462','Cotton denim','Womens jeans, daily wear','NONE',340,260,70),
 (gen_random_uuid(),'moda-muj-08','611030','Knitted polyester','Womens cardigan, daily wear','NONE',340,260,70),
 (gen_random_uuid(),'moda-muj-09','611030','Knitted polyester and cotton','Womens sweatshirt, daily wear','NONE',340,260,70),
 (gen_random_uuid(),'moda-muj-10','611030','Knitted polyester','Womens sweater, daily wear','NONE',340,260,70),
 (gen_random_uuid(),'moda-muj-11','620433','Polyester woven fabric','Womens blazer, daily wear','NONE',360,280,80),
 (gen_random_uuid(),'moda-muj-12','620443','Polyester woven fabric','Womens stage costume','NONE',340,260,70),
 (gen_random_uuid(),'moda-muj-13','611030','Knitted polyester','Womens vest, daily wear','NONE',300,220,40),
 (gen_random_uuid(),'moda-muj-14','620463','Polyester woven fabric','Womens shorts, daily wear','NONE',280,200,30),
 (gen_random_uuid(),'moda-muj-15','621143','Polyester woven fabric','Womens sun protection clothing','NONE',280,200,30),
 (gen_random_uuid(),'moda-muj-17','621143','Polyester woven fabric','Womens jumpsuit, daily wear','NONE',340,260,70),
 (gen_random_uuid(),'moda-muj-20','620640','Polyester chiffon','Womens blouse, daily wear','NONE',300,220,40),
 (gen_random_uuid(),'moda-muj-22','620240','Polyester shell with padding','Womens padded jacket','NONE',400,320,120),
 (gen_random_uuid(),'moda-muj-24','620443','Polyester woven fabric','Womens plus size dress','NONE',340,260,70),
 (gen_random_uuid(),'moda-muj-25','620240','Polyester woven fabric','Womens jacket, daily wear','NONE',380,300,100),
 (gen_random_uuid(),'moda-muj-26','620443','Polyester woven fabric','Womens evening dress','NONE',360,280,80),
 (gen_random_uuid(),'moda-muj-27','620240','Polyester woven fabric','Womens trench coat','NONE',400,320,120),
 (gen_random_uuid(),'moda-muj-28','620443','Polyester woven fabric','Womens qipao dress','NONE',320,240,50),
 (gen_random_uuid(),'moda-muj-29','420310','Synthetic PU leather','Womens jacket, daily wear','NONE',380,300,100),
 -- ── HOMBRE ─────────────────────────────────────────────────────────────────────────────────────
 (gen_random_uuid(),'moda-hom-*' ,'620343','Polyester woven fabric','Mens daily wear','NONE',340,260,70),
 (gen_random_uuid(),'moda-hom-01','610990','Knitted polyester and cotton','Mens t-shirt, daily wear','NONE',300,220,40),
 (gen_random_uuid(),'moda-hom-02','620343','Polyester woven fabric','Mens trousers, daily wear','NONE',340,260,70),
 (gen_random_uuid(),'moda-hom-03','620342','Cotton denim','Mens jeans, daily wear','NONE',360,280,80),
 (gen_random_uuid(),'moda-hom-04','621133','Knitted polyester','Mens tracksuit, daily wear','NONE',380,300,100),
 (gen_random_uuid(),'moda-hom-05','620530','Polyester woven fabric','Mens shirt, daily wear','NONE',320,240,50),
 (gen_random_uuid(),'moda-hom-06','620140','Polyester woven fabric','Mens jacket, daily wear','NONE',380,300,100),
 (gen_random_uuid(),'moda-hom-07','610520','Knitted cotton','Mens polo shirt, daily wear','NONE',300,220,40),
 (gen_random_uuid(),'moda-hom-08','621133','Knitted polyester','Mens sports set, daily wear','NONE',380,300,100),
 (gen_random_uuid(),'moda-hom-09','620343','Polyester woven fabric','Mens shorts, daily wear','NONE',300,220,40),
 (gen_random_uuid(),'moda-hom-10','611030','Knitted polyester','Mens sweater, daily wear','NONE',340,260,70),
 (gen_random_uuid(),'moda-hom-11','620343','Polyester woven fabric','Mens formal trousers','NONE',340,260,70),
 (gen_random_uuid(),'moda-hom-17','620312','Polyester woven fabric','Mens suit, formal wear','NONE',400,320,120),
 (gen_random_uuid(),'moda-hom-19','620342','Cotton denim','Mens denim shorts','NONE',300,220,40),
 (gen_random_uuid(),'moda-hom-30','420310','Synthetic PU leather','Mens jacket, daily wear','NONE',380,300,100),
 -- ── INFANTIL ───────────────────────────────────────────────────────────────────────────────────
 (gen_random_uuid(),'moda-inf-*' ,'620443','Polyester woven fabric','Childrens daily wear','NONE',280,200,40),
 (gen_random_uuid(),'moda-inf-01','620423','Polyester woven fabric','Childrens clothing set','NONE',300,220,50),
 (gen_random_uuid(),'moda-inf-02','620463','Polyester woven fabric','Childrens trousers','NONE',280,200,40),
 (gen_random_uuid(),'moda-inf-03','620443','Polyester woven fabric','Childrens dress','NONE',280,200,40),
 (gen_random_uuid(),'moda-inf-04','610990','Knitted cotton','Childrens t-shirt','NONE',260,180,30),
 (gen_random_uuid(),'moda-inf-06','611596','Knitted polyester and cotton','Childrens socks','NONE',200,150,30),
 (gen_random_uuid(),'moda-inf-07','640299','Synthetic upper and rubber sole','Childrens sandals','NONE',280,180,90),
 (gen_random_uuid(),'moda-inf-08','610822','Knitted polyester','Childrens underwear','NONE',200,150,30),
 (gen_random_uuid(),'moda-inf-09','650500','Knitted polyester and cotton','Childrens hat','NONE',240,200,80),
 (gen_random_uuid(),'moda-inf-10','640411','Textile upper and rubber sole','Childrens sports shoes','NONE',300,190,110),
 (gen_random_uuid(),'moda-inf-12','620530','Cotton woven fabric','Childrens shirt','NONE',280,200,40),
 (gen_random_uuid(),'moda-inf-14','620240','Polyester woven fabric','Childrens jacket','NONE',340,260,80),
 (gen_random_uuid(),'moda-inf-15','640399','Synthetic PU leather upper','Childrens leather shoes','NONE',300,190,110),
 (gen_random_uuid(),'moda-inf-16','640520','Textile upper and rubber sole','Childrens slippers','NONE',280,180,90),
 (gen_random_uuid(),'moda-inf-17','620462','Cotton denim','Childrens jeans','NONE',300,220,50),
 (gen_random_uuid(),'moda-inf-18','620453','Polyester woven fabric','Childrens skirt','NONE',260,180,30),
 (gen_random_uuid(),'moda-inf-19','620443','Polyester woven fabric','Childrens performance costume','NONE',300,220,50),
 (gen_random_uuid(),'moda-inf-22','640419','Textile upper and rubber sole','Childrens shoes','NONE',300,190,110),
 (gen_random_uuid(),'moda-inf-23','611030','Knitted polyester','Childrens sweater','NONE',300,220,50),
 (gen_random_uuid(),'moda-inf-24','611030','Knitted polyester and cotton','Childrens sweatshirt','NONE',300,220,50),
 (gen_random_uuid(),'moda-inf-25','420292','Polyester textile','Childrens backpack','NONE',380,280,140),
 -- ── ROPA INTERIOR / LENCERÍA ───────────────────────────────────────────────────────────────────
 (gen_random_uuid(),'moda-int-*' ,'610822','Knitted polyester','Womens underwear','NONE',200,150,30),
 (gen_random_uuid(),'moda-int-01','610832','Knitted polyester','Womens homewear','NONE',280,200,40),
 (gen_random_uuid(),'moda-int-02','610822','Knitted polyester','Womens briefs','NONE',180,140,25),
 (gen_random_uuid(),'moda-int-03','611595','Knitted cotton','Sports socks','NONE',200,150,30),
 (gen_random_uuid(),'moda-int-04','610822','Knitted polyester and lace','Womens lingerie set','NONE',220,160,30),
 (gen_random_uuid(),'moda-int-05','610990','Knitted cotton','Womens camisole','NONE',200,150,30),
 (gen_random_uuid(),'moda-int-06','621210','Knitted polyester and elastane','Womens seamless bra','NONE',220,160,50),
 (gen_random_uuid(),'moda-int-07','610712','Knitted polyester','Mens boxer briefs','NONE',200,150,30),
 (gen_random_uuid(),'moda-int-08','621210','Knitted polyester and elastane','Womens wireless bra','NONE',220,160,50),
 (gen_random_uuid(),'moda-int-09','610832','Knitted polyester','Womens nightdress','NONE',260,190,35),
 (gen_random_uuid(),'moda-int-10','621210','Knitted polyester and elastane','Womens bandeau bra','NONE',200,150,40),
 (gen_random_uuid(),'moda-int-11','621290','Knitted polyester and elastane','Womens shaping trousers','NONE',220,160,40),
 (gen_random_uuid(),'moda-int-12','610990','Knitted cotton','Womens vest','NONE',200,150,30),
 (gen_random_uuid(),'moda-int-13','611596','Knitted polyester','Womens socks','NONE',180,140,25),
 (gen_random_uuid(),'moda-int-14','610822','Knitted polyester and lace','Womens lingerie','NONE',220,160,30),
 (gen_random_uuid(),'moda-int-15','610463','Knitted polyester and elastane','Womens leggings','NONE',240,180,35),
 (gen_random_uuid(),'moda-int-16','621290','Knitted polyester and elastane','Womens shapewear','NONE',240,180,40),
 (gen_random_uuid(),'moda-int-17','621210','Knitted polyester and elastane','Womens bra set','NONE',220,160,50),
 (gen_random_uuid(),'moda-int-18','621210','Knitted cotton','Teen bra','NONE',200,150,40),
 (gen_random_uuid(),'moda-int-19','621290','Knitted polyester and elastane','Womens abdominal belt','NONE',240,180,40),
 (gen_random_uuid(),'moda-int-21','621210','Knitted polyester and elastane','Womens adjustable bra','NONE',220,160,50),
 (gen_random_uuid(),'moda-int-22','610832','Knitted polyester','Womens robe','NONE',280,200,50),
 (gen_random_uuid(),'moda-int-27','621210','Knitted cotton','Nursing bra','NONE',220,160,50),
 (gen_random_uuid(),'moda-int-28','610822','Knitted polyester','Womens thong briefs','NONE',180,140,25),
 -- ── CALZADO ────────────────────────────────────────────────────────────────────────────────────
 (gen_random_uuid(),'moda-cal-*' ,'640299','Synthetic upper and rubber sole','Daily footwear','NONE',320,200,120),
 (gen_random_uuid(),'moda-cal-01','640299','Synthetic PU upper and rubber sole','Womens casual shoes','NONE',320,200,120),
 (gen_random_uuid(),'moda-cal-02','640419','Textile upper and rubber sole','Womens flat shoes','NONE',310,190,110),
 (gen_random_uuid(),'moda-cal-03','640411','Textile upper and rubber sole','Mens sports shoes','NONE',330,210,130),
 (gen_random_uuid(),'moda-cal-04','640299','Synthetic PU upper and rubber sole','Fashion shoes','NONE',320,200,120),
 (gen_random_uuid(),'moda-cal-05','640411','Textile upper and rubber sole','Womens sports shoes','NONE',320,200,120),
 (gen_random_uuid(),'moda-cal-06','640220','EVA foam','EVA flip flops','NONE',300,180,80),
 (gen_random_uuid(),'moda-cal-07','640411','Textile upper and rubber platform sole','Chunky sole sneakers','NONE',330,210,130),
 (gen_random_uuid(),'moda-cal-08','640299','Synthetic PU upper and rubber sole','Womens high heel sandals','NONE',320,200,120),
 (gen_random_uuid(),'moda-cal-09','640299','Synthetic PU upper and rubber sole','Mens casual shoes','NONE',330,210,130),
 (gen_random_uuid(),'moda-cal-10','640690','EVA foam and textile','Shoe insole','NONE',300,120,30),
 (gen_random_uuid(),'moda-cal-12','640299','Synthetic PU upper and rubber sole','Flat sandals','NONE',310,190,100),
 (gen_random_uuid(),'moda-cal-13','640520','Textile upper and rubber sole','Mens slippers','NONE',310,190,110),
 (gen_random_uuid(),'moda-cal-14','640399','Synthetic PU leather upper','Womens boots','NONE',360,240,140),
 (gen_random_uuid(),'moda-cal-16','640299','EVA foam and synthetic upper','Mens beach shoes','NONE',310,190,100),
 (gen_random_uuid(),'moda-cal-17','640299','Synthetic PU upper and rubber sole','Mens sandals','NONE',320,200,110),
 (gen_random_uuid(),'moda-cal-18','640299','EVA foam','Womens clogs','NONE',310,190,110),
 (gen_random_uuid(),'moda-cal-19','640520','Textile upper and rubber sole','Womens slippers','NONE',300,180,100),
 (gen_random_uuid(),'moda-cal-20','640299','Synthetic PU upper and rubber platform sole','Platform sandals','NONE',320,200,120),
 (gen_random_uuid(),'moda-cal-21','640419','Canvas upper and rubber sole','Mens canvas shoes','NONE',330,210,120),
 (gen_random_uuid(),'moda-cal-22','640299','Synthetic PU upper and rubber sole','Womens heeled shoes','NONE',320,200,120),
 (gen_random_uuid(),'moda-cal-23','640220','PVC and EVA','Flip flops','NONE',300,180,80),
 (gen_random_uuid(),'moda-cal-24','640399','Synthetic PU leather upper','Mens business shoes','NONE',340,220,130),
 (gen_random_uuid(),'moda-cal-25','640419','Canvas upper and rubber sole','Canvas shoes','NONE',320,200,120),
 (gen_random_uuid(),'moda-cal-26','640220','EVA foam','Mens flip flops','NONE',310,190,90),
 (gen_random_uuid(),'moda-cal-28','640399','Synthetic PU leather upper','Casual leather shoes','NONE',330,210,120),
 (gen_random_uuid(),'moda-cal-29','640419','Canvas upper and rubber sole','Womens canvas shoes','NONE',310,190,110),
 -- ── ACCESORIOS ─────────────────────────────────────────────────────────────────────────────────
 (gen_random_uuid(),'moda-acc-*' ,'711719','Zinc alloy','Fashion accessory','NONE',150,120,50),
 (gen_random_uuid(),'moda-acc-01','650500','Knitted polyester and cotton','Adult hat','NONE',250,220,100),
 (gen_random_uuid(),'moda-acc-02','711719','Zinc alloy and glass stone','Fashion necklace','NONE',120,100,30),
 (gen_random_uuid(),'moda-acc-03','711719','Zinc alloy and glass stone','Fashion bracelet','NONE',120,100,30),
 (gen_random_uuid(),'moda-acc-04','711719','Zinc alloy and glass stone','Fashion ring','NONE',100,80,30),
 (gen_random_uuid(),'moda-acc-05','961511','Plastic and metal','Hair clip','NONE',120,100,30),
 (gen_random_uuid(),'moda-acc-06','900410','Plastic frame and resin lens','Sunglasses','NONE',170,80,60),
 (gen_random_uuid(),'moda-acc-07','711719','Zinc alloy and glass stone','Fashion earrings','NONE',100,80,30),
 (gen_random_uuid(),'moda-acc-08','910211','Stainless steel and alloy','Quartz wrist watch','BUILT_IN',120,100,80),
 (gen_random_uuid(),'moda-acc-09','392620','Synthetic PU and alloy buckle','Belt','NONE',250,120,50),
 (gen_random_uuid(),'moda-acc-10','900311','Plastic frame','Eyeglass frame','NONE',170,80,60),
 (gen_random_uuid(),'moda-acc-11','711719','Zinc alloy and glass stone','Fashion pendant','NONE',120,100,30),
 (gen_random_uuid(),'moda-acc-12','611693','Knitted polyester','Adult gloves','NONE',220,160,40),
 (gen_random_uuid(),'moda-acc-14','961511','Plastic and textile','Headband','NONE',150,120,40),
 (gen_random_uuid(),'moda-acc-16','621430','Polyester woven fabric','Scarf','NONE',280,200,60),
 (gen_random_uuid(),'moda-acc-17','711719','Zinc alloy and glass stone','Fashion brooch','NONE',100,80,30),
 (gen_random_uuid(),'moda-acc-18','621430','Polyester satin fabric','Neck scarf','NONE',220,160,40),
 (gen_random_uuid(),'moda-acc-19','711719','Zinc alloy and glass stone','Fashion jewellery set','NONE',180,140,50),
 (gen_random_uuid(),'moda-acc-20','392690','Plastic and metal','Display stand','NONE',300,200,120),
 (gen_random_uuid(),'moda-acc-21','910221','Stainless steel and alloy','Mechanical wrist watch','NONE',120,100,80),
 (gen_random_uuid(),'moda-acc-23','621710','Polyester textile','Dance accessory','NONE',250,180,60),
 (gen_random_uuid(),'moda-acc-25','900490','Plastic frame and resin lens','Reading glasses','NONE',170,80,60),
 -- ── BOLSOS ─────────────────────────────────────────────────────────────────────────────────────
 (gen_random_uuid(),'moda-bol-*' ,'420222','Synthetic PU leather','Fashion bag','NONE',320,250,120),
 (gen_random_uuid(),'moda-bol-01','420222','Synthetic PU leather','Womens shoulder bag','NONE',340,260,130),
 (gen_random_uuid(),'moda-bol-02','420222','Synthetic PU leather','Womens crossbody bag','NONE',280,200,110),
 (gen_random_uuid(),'moda-bol-03','420222','Synthetic PU leather','Womens handbag','NONE',350,270,140),
 (gen_random_uuid(),'moda-bol-05','420292','Polyester textile','Casual backpack','NONE',400,300,150),
 (gen_random_uuid(),'moda-bol-07','420222','Synthetic PU leather and textile','Tote bag','NONE',380,300,140),
 (gen_random_uuid(),'moda-bol-08','420292','Synthetic PU leather','Womens backpack','NONE',380,280,140),
 (gen_random_uuid(),'moda-bol-09','420232','Polyester textile','Cosmetic pouch','NONE',240,180,80),
 (gen_random_uuid(),'moda-bol-10','420292','Polyester textile','Primary school backpack','NONE',420,320,160),
 (gen_random_uuid(),'moda-bol-13','420222','Synthetic PU leather','Small square bag','NONE',250,180,100),
 (gen_random_uuid(),'moda-bol-14','420232','Synthetic PU leather','Card holder','NONE',150,120,40),
 (gen_random_uuid(),'moda-bol-15','420232','Synthetic PU leather','Mens wallet','NONE',200,140,50),
 (gen_random_uuid(),'moda-bol-17','420232','Synthetic PU leather','Small coin purse','NONE',180,130,50),
 (gen_random_uuid(),'moda-bol-19','420222','Synthetic PU leather','Baguette bag','NONE',300,200,110),
 (gen_random_uuid(),'moda-bol-20','420292','Polyester textile','Kindergarten backpack','NONE',360,280,140),
 (gen_random_uuid(),'moda-bol-24','420232','Synthetic PU leather','Mobile phone pouch','NONE',200,140,50),
 (gen_random_uuid(),'moda-bol-25','420222','Synthetic PU leather','Bucket bag','NONE',300,230,130),
 (gen_random_uuid(),'moda-bol-26','420292','Polyester textile','School backpack','NONE',420,320,160),
 (gen_random_uuid(),'moda-bol-28','420292','Polyester textile','Laptop backpack','NONE',430,330,170),
 -- ── BEBÉ ───────────────────────────────────────────────────────────────────────────────────────
 (gen_random_uuid(),'bebe-rop-*','611130','Knitted polyester and cotton','Baby clothing','NONE',260,180,40),
 (gen_random_uuid(),'bebe-rop-01','611130','Knitted cotton','Baby bodysuit','NONE',240,170,35),
 (gen_random_uuid(),'bebe-rop-02','611130','Knitted cotton','Baby clothing','NONE',240,170,35),
 (gen_random_uuid(),'bebe-rop-03','630140','Knitted polyester','Baby blanket','NONE',340,260,90),
 (gen_random_uuid(),'bebe-rop-07','611130','Knitted cotton','Baby clothing','NONE',240,170,35),
 (gen_random_uuid(),'bebe-rop-08','611130','Knitted cotton','Baby clothing','NONE',240,170,35),
 (gen_random_uuid(),'bebe-rop-19','630260','Cotton terry','Baby towel','NONE',300,220,70),
 (gen_random_uuid(),'bebe-edu-*','950300','Plastic and paper','Educational toy','NONE',300,220,90),
 (gen_random_uuid(),'bebe-edu-06','950300','Plastic and paper','Educational toy','NONE',300,220,90),
 (gen_random_uuid(),'bebe-edu-18','950300','Plastic and paper','Educational toy','NONE',300,220,90),
 -- ── DEPORTE ────────────────────────────────────────────────────────────────────────────────────
 (gen_random_uuid(),'dep-rop-*','621143','Knitted polyester and elastane','Sportswear','NONE',300,220,50),
 (gen_random_uuid(),'dep-rop-03','621143','Knitted polyester and elastane','Sportswear','NONE',300,220,50),
 (gen_random_uuid(),'dep-rop-04','621143','Knitted polyester and elastane','Sportswear','NONE',300,220,50),
 (gen_random_uuid(),'dep-rop-11','621143','Knitted polyester and elastane','Sportswear','NONE',300,220,50),
 (gen_random_uuid(),'dep-rop-14','621143','Knitted polyester and elastane','Sportswear','NONE',300,220,50),
 (gen_random_uuid(),'dep-rop-19','621143','Knitted polyester and elastane','Sportswear','NONE',300,220,50),
 (gen_random_uuid(),'dep-rop-21','621143','Knitted polyester and elastane','Sportswear','NONE',300,220,50),
 (gen_random_uuid(),'dep-acc-*','950691','Plastic and steel','Fitness accessory','NONE',300,200,120),
 (gen_random_uuid(),'dep-acc-01','950691','Plastic and steel','Fitness accessory','NONE',300,200,120),
 (gen_random_uuid(),'dep-acc-21','950691','Plastic and steel','Fitness accessory','NONE',300,200,120),
 (gen_random_uuid(),'dep-eje-*','950691','Plastic and steel','Fitness equipment','NONE',350,250,150),
 (gen_random_uuid(),'dep-eje-02','950691','Plastic and steel','Fitness equipment','NONE',350,250,150),
 -- ── HOGAR / PEQUEÑO ELECTRODOMÉSTICO ───────────────────────────────────────────────────────────
 (gen_random_uuid(),'hog-ele-*','850980','Plastic and metal','Household appliance','BUILT_IN',300,250,200),
 (gen_random_uuid(),'hog-ele-01','850980','Plastic and metal','Aroma diffuser','NONE',220,180,180),
 (gen_random_uuid(),'hog-ele-02','841451','Plastic and metal','USB fan','BUILT_IN',250,200,150),
 (gen_random_uuid(),'hog-ele-03','841451','Plastic and metal','Mini fan','BUILT_IN',250,200,150),
 (gen_random_uuid(),'hog-ele-04','851822','Plastic and metal','Bluetooth speaker','BUILT_IN',250,200,150),
 (gen_random_uuid(),'hog-ele-05','850980','Plastic and metal','Household appliance','NONE',300,250,200),
 (gen_random_uuid(),'hog-ele-06','850940','Plastic and stainless steel','Blender','NONE',350,280,300),
 (gen_random_uuid(),'hog-ele-07','850980','Plastic and metal','Kitchen appliance','NONE',250,200,180),
 (gen_random_uuid(),'hog-ele-08','841451','Plastic and metal','Electric fan','NONE',500,400,250),
 (gen_random_uuid(),'hog-ele-11','850980','Plastic and metal','Humidifier','NONE',280,220,250),
 (gen_random_uuid(),'hog-ele-13','901910','Plastic and textile','Massage pad','BUILT_IN',350,280,150),
 (gen_random_uuid(),'hog-ele-17','842230','Plastic and metal','Vacuum sealer','NONE',420,280,150),
 (gen_random_uuid(),'hog-ele-18','851679','Stainless steel and plastic','Electric kettle','NONE',300,250,300),
 (gen_random_uuid(),'hog-ele-19','851660','Stainless steel and plastic','Electric cooker','NONE',400,350,300),
 (gen_random_uuid(),'hog-ele-21','851640','Plastic and metal','Garment steamer','NONE',350,280,200),
 (gen_random_uuid(),'hog-ele-22','851660','Plastic and metal','Air fryer','NONE',420,380,400),
 (gen_random_uuid(),'hog-ele-23','851030','Plastic and metal','Epilator','BUILT_IN',220,160,90),
 (gen_random_uuid(),'hog-ele-25','851840','Plastic and metal','Audio amplifier','NONE',350,280,150),
 (gen_random_uuid(),'hog-ele-28','841451','Plastic and metal','Camping fan','BUILT_IN',300,250,200),
 (gen_random_uuid(),'hog-ele-29','850811','Plastic and metal','Household vacuum cleaner','BUILT_IN',450,300,250),
 (gen_random_uuid(),'hog-mue-*','940320','Metal and wood','Household furniture','NONE',600,450,300),
 (gen_random_uuid(),'hog-mue-01','940130','Metal, plastic and fabric','Office chair','NONE',700,500,350),
 (gen_random_uuid(),'hog-mue-10','940320','Metal and wood','Household furniture','NONE',600,450,300),
 (gen_random_uuid(),'hog-tex-*','630260','Cotton and polyester textile','Home textile','NONE',350,270,100),
 (gen_random_uuid(),'hog-tex-08','630260','Cotton and polyester textile','Home textile','NONE',350,270,100),
 (gen_random_uuid(),'hog-tex-16','630260','Cotton and polyester textile','Home textile','NONE',350,270,100),
 (gen_random_uuid(),'hog-tex-19','630140','Polyester textile','Blanket','NONE',400,300,150),
 (gen_random_uuid(),'hog-tex-27','940490','Polyester and foam','Cushion','NONE',400,300,150),
 (gen_random_uuid(),'hog-lim-*','392490','Plastic','Household cleaning article','NONE',350,250,250),
 (gen_random_uuid(),'hog-lim-10','392490','Plastic','Waste bin','NONE',400,300,300),
 (gen_random_uuid(),'hog-lim-23','960390','Plastic and fibre','Cleaning brush','NONE',350,150,100),
 (gen_random_uuid(),'hog-art-*','392690','Plastic and metal','Household article','NONE',300,220,120),
 (gen_random_uuid(),'hog-art-20','640690','Plastic and textile','Footwear accessory','NONE',300,180,100),
 (gen_random_uuid(),'hog-coc-*','392410','Plastic','Kitchen article','NONE',320,240,150),
 (gen_random_uuid(),'hog-coc-07','732393','Stainless steel','Kitchen article','NONE',320,240,150),
 (gen_random_uuid(),'hog-coc-16','392410','Plastic','Kitchen article','NONE',320,240,150),
 (gen_random_uuid(),'hog-ilu-*','940542','Plastic and LED','LED lamp','NONE',300,200,150),
 (gen_random_uuid(),'hog-ilu-09','940542','Plastic and LED','LED lamp','NONE',300,200,150),
 (gen_random_uuid(),'hogar-cocina-bano-vajilla','392410','Plastic','Kitchen and tableware article','NONE',320,240,150),
 (gen_random_uuid(),'hogar-y-jardin','392690','Plastic and metal','Household article','NONE',350,250,180),
 -- ── BELLEZA E HIGIENE ──────────────────────────────────────────────────────────────────────────
 (gen_random_uuid(),'bell-dis-*','851632','Plastic and metal','Personal care appliance','BUILT_IN',280,200,120),
 (gen_random_uuid(),'bell-dis-01','851632','Plastic and ceramic','Hair curler','NONE',350,120,100),
 (gen_random_uuid(),'bell-dis-03','851631','Plastic and metal','Hair dryer','NONE',300,250,150),
 (gen_random_uuid(),'bell-dis-04','851020','Plastic and stainless steel','Hair clipper','BUILT_IN',250,180,90),
 (gen_random_uuid(),'bell-dis-05','851632','Plastic and metal','Personal care appliance','BUILT_IN',280,200,120),
 (gen_random_uuid(),'bell-dis-06','851632','Plastic and metal','Personal care appliance','BUILT_IN',280,200,120),
 (gen_random_uuid(),'bell-dis-07','851020','Plastic and stainless steel','Personal care appliance','BUILT_IN',250,180,90),
 (gen_random_uuid(),'bell-dis-08','850980','Plastic and metal','Dental water flosser','BUILT_IN',280,200,150),
 (gen_random_uuid(),'bell-dis-09','850980','Plastic and nylon','Electric toothbrush','BUILT_IN',280,150,90),
 (gen_random_uuid(),'bell-dis-16','854370','Plastic and metal','Toothbrush steriliser','BUILT_IN',250,180,120),
 (gen_random_uuid(),'bell-dis-28','851632','Plastic and metal','Personal care appliance','BUILT_IN',280,200,120),
 (gen_random_uuid(),'bell-hig-*','392490','Plastic','Personal hygiene article','NONE',280,200,120),
 (gen_random_uuid(),'bell-hig-01','392490','Plastic','Body care set','NONE',300,220,140),
 (gen_random_uuid(),'bell-hig-10','392490','Plastic','Personal hygiene article','NONE',280,200,120),
 (gen_random_uuid(),'bell-cos-*','960329','Plastic and nylon','Cosmetic brush','NONE',250,150,60),
 (gen_random_uuid(),'bell-cos-03','960329','Plastic and nylon','Hair brush','NONE',280,150,60),
 (gen_random_uuid(),'belleza-y-cuidado','392490','Plastic','Personal care article','NONE',280,200,120),
 -- ── ELECTRÓNICA ────────────────────────────────────────────────────────────────────────────────
 (gen_random_uuid(),'electronicas','851762','Plastic and metal','Consumer electronics','BUILT_IN',250,200,120),
 (gen_random_uuid(),'ele-tel-*','851810','Plastic and metal','Audio equipment','BUILT_IN',280,220,120),
 (gen_random_uuid(),'ele-tel-12','851810','Plastic and metal','Microphone and sound system','BUILT_IN',280,220,120),
 (gen_random_uuid(),'ele-iot-*','851762','Plastic and metal','Wireless device','BUILT_IN',200,150,80),
 (gen_random_uuid(),'ele-iot-08','851762','Plastic and metal','Wireless device','BUILT_IN',200,150,80),
 (gen_random_uuid(),'ele-sem-*','851822','Plastic and metal','Speaker','BUILT_IN',250,200,150),
 (gen_random_uuid(),'ele-sem-04','851822','Plastic and metal','Speaker','BUILT_IN',250,200,150),
 (gen_random_uuid(),'ind-amb-*','842139','Plastic and metal','Air purification equipment','BUILT_IN',300,250,200),
 (gen_random_uuid(),'ind-amb-02','842139','Plastic and metal','Air purification equipment','BUILT_IN',300,250,200),
 (gen_random_uuid(),'aut-pza-*','870899','Plastic and metal','Vehicle accessory','NONE',300,220,120),
 (gen_random_uuid(),'aut-pza-22','870899','Plastic and metal','Vehicle accessory','NONE',300,220,120),
 -- ── UNIFORME / PUBLICIDAD ──────────────────────────────────────────────────────────────────────
 (gen_random_uuid(),'moda-tra-*','621143','Polyester woven fabric','Workwear','NONE',340,260,70),
 (gen_random_uuid(),'moda-tra-02','621143','Polyester woven fabric','Workwear','NONE',340,260,70),
 (gen_random_uuid(),'moda-tra-03','621143','Polyester woven fabric','Work uniform','NONE',340,260,70)
ON CONFLICT (category_slug) DO NOTHING;

-- ── Siembra de los productos ya cargados ─────────────────────────────────────────────────────────
-- 1) Perfil EXACTO de la categoría-hoja. No pisa un hs_code ya informado a mano.
UPDATE product p SET
    hs_code          = coalesce(nullif(trim(p.hs_code),''), cp.hs_code),
    customs_material = coalesce(p.customs_material, cp.material),
    customs_usage    = coalesce(p.customs_usage, cp.usage_text),
    battery_type     = CASE WHEN p.battery_type IS NULL OR p.battery_type = 'NONE' THEN cp.battery_type ELSE p.battery_type END,
    length_mm        = CASE WHEN coalesce(p.length_mm,0) = 0 THEN cp.pack_length_mm ELSE p.length_mm END,
    width_mm         = CASE WHEN coalesce(p.width_mm,0)  = 0 THEN cp.pack_width_mm  ELSE p.width_mm  END,
    height_mm        = CASE WHEN coalesce(p.height_mm,0) = 0 THEN cp.pack_height_mm ELSE p.height_mm END,
    updated_at       = now()
FROM category c, category_customs_profile cp
WHERE c.id = p.category_id AND cp.category_slug = c.slug;

-- 2) Fallback por FAMILIA para las categorías-hoja sin perfil propio ('moda-muj-*', 'hog-ele-*'…).
UPDATE product p SET
    hs_code          = coalesce(nullif(trim(p.hs_code),''), cp.hs_code),
    customs_material = coalesce(p.customs_material, cp.material),
    customs_usage    = coalesce(p.customs_usage, cp.usage_text),
    battery_type     = CASE WHEN p.battery_type IS NULL OR p.battery_type = 'NONE' THEN cp.battery_type ELSE p.battery_type END,
    length_mm        = CASE WHEN coalesce(p.length_mm,0) = 0 THEN cp.pack_length_mm ELSE p.length_mm END,
    width_mm         = CASE WHEN coalesce(p.width_mm,0)  = 0 THEN cp.pack_width_mm  ELSE p.width_mm  END,
    height_mm        = CASE WHEN coalesce(p.height_mm,0) = 0 THEN cp.pack_height_mm ELSE p.height_mm END,
    updated_at       = now()
FROM category c, category_customs_profile cp
WHERE c.id = p.category_id
  AND cp.category_slug = split_part(c.slug,'-',1) || '-' || split_part(c.slug,'-',2) || '-*'
  AND (p.customs_material IS NULL OR coalesce(trim(p.hs_code),'') = '' OR coalesce(p.length_mm,0) = 0);

CREATE INDEX IF NOT EXISTS idx_product_battery_type ON product (battery_type);
