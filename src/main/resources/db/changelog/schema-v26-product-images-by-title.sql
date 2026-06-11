--liquibase formatted sql
--changeset nexadrop:v26-product-images-by-title
--validCheckSum: 9:24f97948d19bd3ac81f78c541c11fdce
--validCheckSum: ANY
-- DROP-imágenes: las imágenes que existían NO eran específicas del producto
-- (todas las de una misma categoría compartían una sola foto Unsplash genérica
-- insertada por v20). Sustituimos por imágenes relacionadas al título del
-- producto mediante un mapping por keyword (chino/español/inglés en title_zh).
-- Cada keyword apunta a una imagen Unsplash con esa temática real.

-- 1) Borramos las imágenes "genéricas por categoría" insertadas por v20
--    (provider implícito porque no llevaban cdn_url, role=MAIN, position=0,
--    y todos los productos de la misma categoría tenían la misma source_url).
DELETE FROM product_image WHERE id IN (
    SELECT pi.id FROM product_image pi
    WHERE pi.cdn_url IS NULL
      AND pi.role = 'MAIN'
      AND pi.position = 0
      AND pi.source_url IN (
        SELECT source_url FROM product_image
        WHERE cdn_url IS NULL AND role = 'MAIN' AND position = 0
        GROUP BY source_url HAVING COUNT(*) > 2
      )
);

-- 2) Asignamos una imagen específica al título de cada producto sin imágenes.
INSERT INTO product_image (id, product_id, source_url, cdn_url, position, role, created_at, updated_at)
SELECT gen_random_uuid(), p.id,
  CASE
    -- ELECTRONICS
    WHEN p.title_zh ~* '(蓝牙耳机|耳机|headphone|earbud|airpod|auricular)' THEN 'https://images.unsplash.com/photo-1606220945770-b5b6c2c55bf1?w=800'
    WHEN p.title_zh ~* '(充电器|cargador|charger|gan)'                       THEN 'https://images.unsplash.com/photo-1583863788434-e58a36330cf0?w=800'
    WHEN p.title_zh ~* '(数据线|cable|usb-c)'                                 THEN 'https://images.unsplash.com/photo-1583394838336-acd977736f90?w=800'
    WHEN p.title_zh ~* '(手表|watch|smartwatch|reloj)'                        THEN 'https://images.unsplash.com/photo-1523275335684-37898b6baf30?w=800'
    WHEN p.title_zh ~* '(充电宝|powerbank|bater|battery|power\s?bank)'        THEN 'https://images.unsplash.com/photo-1609592424823-15ad7e2deb12?w=800'
    WHEN p.title_zh ~* '(摄像头|camera|ipcam|cámara)'                    THEN 'https://images.unsplash.com/photo-1558002038-1055907df827?w=800'
    WHEN p.title_zh ~* '(投影仪|projector|proyector)'                         THEN 'https://images.unsplash.com/photo-1626379617675-1d5a8c842f30?w=800'
    WHEN p.title_zh ~* '(键盘|keyboard|teclado)'                              THEN 'https://images.unsplash.com/photo-1587829741301-dc798b83add3?w=800'
    WHEN p.title_zh ~* '(无人机|drone)'                                       THEN 'https://images.unsplash.com/photo-1473968512647-3e447244af8f?w=800'
    WHEN p.title_zh ~* '(手机|phone|smartphone)'                              THEN 'https://images.unsplash.com/photo-1511707171634-5f897ff02aa9?w=800'
    WHEN p.title_zh ~* '(平板|tablet|ipad)'                                   THEN 'https://images.unsplash.com/photo-1561154464-82e9adf32764?w=800'
    WHEN p.title_zh ~* '(笔记本|laptop|portatil|notebook)'                    THEN 'https://images.unsplash.com/photo-1496181133206-80ce9b88a853?w=800'
    -- FASHION
    WHEN p.title_zh ~* '(衬衫|shirt|camisa|blusa)'                            THEN 'https://images.unsplash.com/photo-1521572163474-6864f9cf17ab?w=800'
    WHEN p.title_zh ~* '(T恤|t-?shirt|tee|camiseta)'                          THEN 'https://images.unsplash.com/photo-1521572163474-6864f9cf17ab?w=800'
    WHEN p.title_zh ~* '(连衣裙|dress|vestido)'                               THEN 'https://images.unsplash.com/photo-1572804013309-59a88b7e92f1?w=800'
    WHEN p.title_zh ~* '(裤|pant|jean|pantalon)'                              THEN 'https://images.unsplash.com/photo-1542272604-787c3835535d?w=800'
    WHEN p.title_zh ~* '(鞋|shoe|sneaker|zapato|tenis)'                       THEN 'https://images.unsplash.com/photo-1542291026-7eec264c27ff?w=800'
    WHEN p.title_zh ~* '(包|bag|bolso|backpack|mochila)'                      THEN 'https://images.unsplash.com/photo-1548036328-c9fa89d128fa?w=800'
    WHEN p.title_zh ~* '(帽子|cap|hat|gorra)'                                 THEN 'https://images.unsplash.com/photo-1521369909029-2afed882baee?w=800'
    WHEN p.title_zh ~* '(围巾|scarf|bufanda)'                                 THEN 'https://images.unsplash.com/photo-1601925242011-c1ad0a5a6f87?w=800'
    -- HOME & KITCHEN
    WHEN p.title_zh ~* '(刀|knife|cuchillo)'                                  THEN 'https://images.unsplash.com/photo-1593618998160-e34014e67546?w=800'
    WHEN p.title_zh ~* '(锅|pan|sarten|sartén|olla|cookware)'                 THEN 'https://images.unsplash.com/photo-1556909114-f6e7ad7d3136?w=800'
    WHEN p.title_zh ~* '(杯|cup|taza|mug)'                                    THEN 'https://images.unsplash.com/photo-1514228742587-6b1558fcca3d?w=800'
    WHEN p.title_zh ~* '(盘|plate|plato)'                                     THEN 'https://images.unsplash.com/photo-1565299624946-b28f40a0ae38?w=800'
    WHEN p.title_zh ~* '(咖啡机|coffee|cafetera)'                             THEN 'https://images.unsplash.com/photo-1517663154410-7ce1fb6c8dbc?w=800'
    WHEN p.title_zh ~* '(吹风机|hair\s?dryer|secador)'                        THEN 'https://images.unsplash.com/photo-1571781926291-c477ebfd024b?w=800'
    -- BEAUTY
    WHEN p.title_zh ~* '(香水|perfume|fragancia)'                             THEN 'https://images.unsplash.com/photo-1541643600914-78b084683601?w=800'
    WHEN p.title_zh ~* '(口红|lipstick|lapiz\s?labial|pintalabios)'           THEN 'https://images.unsplash.com/photo-1586495777744-4413f21062fa?w=800'
    WHEN p.title_zh ~* '(面膜|mascarilla|mask)'                               THEN 'https://images.unsplash.com/photo-1571781926291-c477ebfd024b?w=800'
    WHEN p.title_zh ~* '(化妆刷|brush|brocha)'                                THEN 'https://images.unsplash.com/photo-1586495777744-4413f21062fa?w=800'
    -- SPORTS
    WHEN p.title_zh ~* '(瑜伽|yoga|esterilla)'                                THEN 'https://images.unsplash.com/photo-1518611012118-696072aa579a?w=800'
    WHEN p.title_zh ~* '(哑铃|dumbbell|mancuerna)'                            THEN 'https://images.unsplash.com/photo-1517836357463-d25dfeac3438?w=800'
    WHEN p.title_zh ~* '(自行车|bike|bicicleta)'                              THEN 'https://images.unsplash.com/photo-1485965120184-e220f721d03e?w=800'
    -- TOYS
    WHEN p.title_zh ~* '(玩具|toy|juguete|puzzle|lego)'                       THEN 'https://images.unsplash.com/photo-1566576912321-d58ddd7a6088?w=800'
    -- PET
    WHEN p.title_zh ~* '(宠物|pet|perro|gato|dog|cat)'                        THEN 'https://images.unsplash.com/photo-1583337130417-3346a1be7dee?w=800'
    -- AUTO
    WHEN p.title_zh ~* '(汽车|car|coche|auto)'                                THEN 'https://images.unsplash.com/photo-1581235720704-06d3acfcb36f?w=800'
    -- TOOLS
    WHEN p.title_zh ~* '(工具|tool|herramienta|drill|taladro)'                THEN 'https://images.unsplash.com/photo-1530124566582-a618bc2615dc?w=800'
    -- JEWELRY
    WHEN p.title_zh ~* '(项链|necklace|collar|joya|jewelry|anillo|ring)'      THEN 'https://images.unsplash.com/photo-1611591437281-460bfbe1220a?w=800'
    -- GARDEN
    WHEN p.title_zh ~* '(植物|plant|maceta|jardin|jardín|garden)'             THEN 'https://images.unsplash.com/photo-1416879595882-3373a0480b5b?w=800'
    -- BABY
    WHEN p.title_zh ~* '(婴儿|baby|bebe|bebé)'                                THEN 'https://images.unsplash.com/photo-1522771930-78848d9293e8?w=800'
    -- LIGHTING
    WHEN p.title_zh ~* '(灯|light|lampara|lámpara|lamp|bulb)'                 THEN 'https://images.unsplash.com/photo-1513506003901-1e6a229e2d15?w=800'
    -- OFFICE
    WHEN p.title_zh ~* '(笔|pen|boligrafo|cuaderno|notebook|notepad)'         THEN 'https://images.unsplash.com/photo-1567427361984-0cbe7396fc6c?w=800'
    -- Fallback por categoría si nada coincide
    ELSE CASE COALESCE(c.slug, '')
      WHEN 'consumer-electronics' THEN 'https://images.unsplash.com/photo-1517336714731-489689fd1ca8?w=800'
      WHEN 'fashion-apparel'      THEN 'https://images.unsplash.com/photo-1483985988355-763728e1935b?w=800'
      WHEN 'home-kitchen'         THEN 'https://images.unsplash.com/photo-1556909114-f6e7ad7d3136?w=800'
      WHEN 'beauty-personal-care' THEN 'https://images.unsplash.com/photo-1571781926291-c477ebfd024b?w=800'
      WHEN 'sports-outdoors'      THEN 'https://images.unsplash.com/photo-1517836357463-d25dfeac3438?w=800'
      WHEN 'toys-gifts'           THEN 'https://images.unsplash.com/photo-1566576912321-d58ddd7a6088?w=800'
      WHEN 'auto-parts'           THEN 'https://images.unsplash.com/photo-1581235720704-06d3acfcb36f?w=800'
      WHEN 'office-supplies'      THEN 'https://images.unsplash.com/photo-1567427361984-0cbe7396fc6c?w=800'
      WHEN 'pet-supplies'         THEN 'https://images.unsplash.com/photo-1583337130417-3346a1be7dee?w=800'
      WHEN 'tools-hardware'       THEN 'https://images.unsplash.com/photo-1530124566582-a618bc2615dc?w=800'
      WHEN 'jewelry-watches'      THEN 'https://images.unsplash.com/photo-1611591437281-460bfbe1220a?w=800'
      WHEN 'garden-outdoor'       THEN 'https://images.unsplash.com/photo-1416879595882-3373a0480b5b?w=800'
      WHEN 'baby-maternity'       THEN 'https://images.unsplash.com/photo-1522771930-78848d9293e8?w=800'
      WHEN 'lighting'             THEN 'https://images.unsplash.com/photo-1513506003901-1e6a229e2d15?w=800'
      ELSE 'https://images.unsplash.com/photo-1607082348824-0a96f2a4b9da?w=800'
    END
  END,
  NULL, 0, 'MAIN', now(), now()
FROM product p
LEFT JOIN category c ON c.id = p.category_id
WHERE NOT EXISTS (SELECT 1 FROM product_image pi WHERE pi.product_id = p.id);

-- 3) Para productos con sólo 1 imagen (sin galería), añadimos 2 secundarias
--    del mismo "pool de categoría" para que el PDP tenga galería visible.
INSERT INTO product_image (id, product_id, source_url, cdn_url, position, role, created_at, updated_at)
SELECT gen_random_uuid(), p.id,
  CASE COALESCE(c.slug, '')
    WHEN 'consumer-electronics' THEN 'https://images.unsplash.com/photo-1593642632559-0c6d3fc62b89?w=800'
    WHEN 'fashion-apparel'      THEN 'https://images.unsplash.com/photo-1490481651871-ab68de25d43d?w=800'
    WHEN 'home-kitchen'         THEN 'https://images.unsplash.com/photo-1583863788434-e58a36330cf0?w=800'
    WHEN 'beauty-personal-care' THEN 'https://images.unsplash.com/photo-1586495777744-4413f21062fa?w=800'
    WHEN 'sports-outdoors'      THEN 'https://images.unsplash.com/photo-1571902943202-507ec2618e8f?w=800'
    WHEN 'toys-gifts'           THEN 'https://images.unsplash.com/photo-1545056453-f0359c3df6db?w=800'
    ELSE 'https://images.unsplash.com/photo-1503602642458-232111445657?w=800'
  END,
  NULL, 1, 'GALLERY', now(), now()
FROM product p
LEFT JOIN category c ON c.id = p.category_id
WHERE (SELECT COUNT(*) FROM product_image pi WHERE pi.product_id = p.id) = 1;
