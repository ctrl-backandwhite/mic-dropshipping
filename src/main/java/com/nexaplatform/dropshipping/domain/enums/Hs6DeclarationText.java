package com.nexaplatform.dropshipping.domain.enums;

import java.util.Locale;
import java.util.Optional;

/**
 * El texto oficial de cada subpartida del Sistema Armonizado presente en el catálogo, en inglés y en
 * chino.
 *
 * <p>Es la materia prima del <b>borrador</b> de descripciones de {@code customs_declaration_group}: un
 * grupo recién sembrado se redacta como «texto de la partida · material · uso» y espera a que una
 * persona lo apruebe. Nada de lo que hay aquí llega a una aduana sin esa firma — un grupo sin aprobar
 * no agrupa, así que un borrador desafortunado cuesta 3 EUR de más, nunca una declaración falsa.
 *
 * <p><b>Por qué un enum y no una tabla.</b> Estos textos no los edita nadie: son la nomenclatura, y
 * cambian cuando cambia la nomenclatura, es decir, con un despliegue. Lo que sí se edita —la
 * descripción concreta de cada terna— vive en la tabla, que es donde se aprueba.
 *
 * <p><b>El chino va suelto, sin material ni uso.</b> El {@code CName} lo valida el transportista
 * exigiendo ideogramas; pegarle detrás un «· Cotton · Casual wear» del catálogo, que está en inglés,
 * dejaría media línea de la declaración en otro idioma sin ganar precisión. Quien apruebe el grupo
 * afina el texto si hace falta.
 *
 * <p>Son las 67 subpartidas que hoy tiene el catálogo. Una partida que no esté aquí no rompe nada: el
 * borrador cae en un texto genérico con el número de la partida, igual de pendiente de aprobación.
 */
public enum Hs6DeclarationText {

    DRESSES_SYNTHETIC_WOVEN("620443", "Women's or girls' dresses, of synthetic fibres", "女式合成纤维制连衣裙"),
    TSHIRTS_OTHER_KNITTED("610990", "T-shirts, singlets and other vests, knitted, of other textile materials",
            "针织其他纺织材料制T恤衫及背心"),
    ENSEMBLES_SYNTHETIC_WOVEN("620423", "Women's or girls' ensembles, of synthetic fibres", "女式合成纤维制便服套装"),
    FOOTWEAR_RUBBER_PLASTICS_OTHER("640299", "Other footwear with outer soles and uppers of rubber or plastics",
            "橡胶或塑料制外底及鞋面的其他鞋靴"),
    BRIEFS_KNITTED_MANMADE("610822", "Women's or girls' briefs and panties, knitted, of man-made fibres",
            "针织化纤制女式三角裤及短裤"),
    HANDBAGS_PLASTIC_TEXTILE("420222", "Handbags with outer surface of plastic sheeting or of textile materials",
            "塑料片或纺织材料制面的手提包"),
    SPORTS_FOOTWEAR_TEXTILE_UPPERS("640411",
            "Sports footwear with outer soles of rubber or plastics and uppers of textile materials",
            "纺织材料制鞋面的运动鞋"),
    PULLOVERS_KNITTED_MANMADE("611030",
            "Jerseys, pullovers, cardigans and similar articles, knitted, of man-made fibres",
            "针织化纤制套头衫、开襟衫及类似品"),
    MENS_TROUSERS_SYNTHETIC_WOVEN("620343", "Men's or boys' trousers and shorts, of synthetic fibres",
            "男式合成纤维制长裤及短裤"),
    WRISTWATCHES_ELECTRIC_MECHANICAL_DISPLAY("910211",
            "Wrist-watches, electrically operated, with mechanical display only", "电子机械指针式手表"),
    SKIRTS_SYNTHETIC_WOVEN("620453", "Women's or girls' skirts and divided skirts, of synthetic fibres",
            "女式合成纤维制裙子及裙裤"),
    WOMENS_TROUSERS_COTTON_WOVEN("620462", "Women's or girls' trousers and shorts, of cotton", "女式棉制长裤及短裤"),
    BRASSIERES("621210", "Brassieres", "胸罩"),
    BLOUSES_MANMADE_WOVEN("620640", "Women's or girls' blouses and shirts, of man-made fibres", "女式化纤制衬衫"),
    WOMENS_TROUSERS_SYNTHETIC_WOVEN("620463", "Women's or girls' trousers and shorts, of synthetic fibres",
            "女式合成纤维制长裤及短裤"),
    MENS_SHIRTS_MANMADE_WOVEN("620530", "Men's or boys' shirts, of man-made fibres", "男式化纤制衬衫"),
    IMITATION_JEWELLERY_BASE_METAL("711719", "Imitation jewellery of base metal", "贱金属制仿首饰"),
    OTHER_GARMENTS_WOMENS_MANMADE("621143", "Other women's or girls' garments, of man-made fibres",
            "女式化纤制其他服装"),
    HEADGEAR_TEXTILE("650500", "Hats and other headgear, knitted or made up from textile fabric",
            "针织或纺织物制帽类"),
    FOOTWEAR_STRAPS_PLUGS("640220",
            "Footwear with upper straps or thongs assembled to the sole by plugs, of rubber or plastics",
            "橡胶或塑料制带扣鞋面的鞋靴"),
    SUNGLASSES("900410", "Sunglasses", "太阳镜"),
    NIGHTWEAR_KNITTED_MANMADE("610832", "Women's or girls' nightdresses and pyjamas, knitted, of man-made fibres",
            "针织化纤制女式睡衣及睡裙"),
    FOOTWEAR_LEATHER_UPPERS_OTHER("640399", "Other footwear with uppers of leather", "皮革制鞋面的其他鞋靴"),
    CONTAINERS_PLASTIC_TEXTILE("420292",
            "Other containers with outer surface of plastic sheeting or of textile materials",
            "塑料片或纺织材料制面的其他容器"),
    WOMENS_JACKETS_SYNTHETIC_WOVEN("620433", "Women's or girls' jackets and blazers, of synthetic fibres",
            "女式合成纤维制上衣"),
    MENS_ANORAKS_MANMADE("620140",
            "Men's or boys' anoraks, windcheaters and similar articles, of man-made fibres",
            "男式化纤制风雪大衣及类似品"),
    APPAREL_ACCESSORIES_PLASTICS("392620", "Articles of apparel and clothing accessories, of plastics",
            "塑料制服装及衣着附件"),
    WOMENS_ANORAKS_MANMADE("620240",
            "Women's or girls' anoraks, windcheaters and similar articles, of man-made fibres",
            "女式化纤制风雪大衣及类似品"),
    MENS_TROUSERS_COTTON_WOVEN("620342", "Men's or boys' trousers and shorts, of cotton", "男式棉制长裤及短裤"),
    OTHER_GARMENTS_MENS_MANMADE("621133", "Other men's or boys' garments, of man-made fibres", "男式化纤制其他服装"),
    MENS_SHIRTS_KNITTED_MANMADE("610520", "Men's or boys' shirts, knitted, of man-made fibres", "针织化纤制男式衬衫"),
    BABIES_GARMENTS_KNITTED_SYNTHETIC("611130",
            "Babies' garments and clothing accessories, knitted, of synthetic fibres",
            "针织合成纤维制婴儿服装及衣着附件"),
    FOOTWEAR_TEXTILE_UPPERS_OTHER("640419",
            "Other footwear with outer soles of rubber or plastics and uppers of textile materials",
            "纺织材料制鞋面的其他鞋靴"),
    BODY_SUPPORT_ARTICLES_OTHER("621290", "Corsets, braces, suspenders and similar articles",
            "束腰、背带、吊袜带及类似品"),
    FOOTWEAR_TEXTILE_UPPERS_NESOI("640520", "Other footwear with uppers of textile materials",
            "纺织材料制鞋面的其他鞋靴"),
    MENS_SUITS_SYNTHETIC("620312", "Men's or boys' suits, of synthetic fibres", "男式合成纤维制西服套装"),
    TOILET_KITCHEN_LINEN_TERRY_COTTON("630260", "Toilet linen and kitchen linen, of terry towelling, of cotton",
            "棉制毛巾织物盥洗及厨房用织物制品"),
    MENS_UNDERPANTS_KNITTED_MANMADE("610712", "Men's or boys' underpants and briefs, knitted, of man-made fibres",
            "针织化纤制男式内裤及三角裤"),
    HOSIERY_COTTON_KNITTED("611595", "Other hosiery, knitted, of cotton", "针织棉制其他袜类"),
    SCARVES_SYNTHETIC("621430", "Shawls, scarves, mufflers and the like, of synthetic fibres",
            "合成纤维制披巾、围巾及类似品"),
    HOSIERY_SYNTHETIC_KNITTED("611596", "Other hosiery, knitted, of synthetic fibres", "针织合成纤维制其他袜类"),
    POCKET_ARTICLES_PLASTIC_TEXTILE("420232",
            "Articles carried in the pocket or handbag, with outer surface of plastic sheeting or of textile materials",
            "塑料片或纺织材料制面的随身携带品"),
    APPAREL_LEATHER("420310", "Articles of apparel, of leather or of composition leather", "皮革或再生皮革制服装"),
    DATA_TRANSMISSION_APPARATUS("851762",
            "Machines for the reception, conversion and transmission of voice, images or other data",
            "语音、图像或其他数据的接收、转换及发送设备"),
    HOUSEHOLD_ARTICLES_PLASTICS_OTHER("392490", "Other household and hygienic articles, of plastics",
            "塑料制其他家庭及卫生用品"),
    COMBS_HAIR_SLIDES_PLASTICS("961511", "Combs, hair-slides and the like, of hard rubber or plastics",
            "硬橡胶或塑料制梳子、发夹及类似品"),
    WRISTWATCHES_AUTOMATIC_WINDING("910221", "Wrist-watches, other, with automatic winding", "自动上弦机械手表"),
    BLANKETS_SYNTHETIC("630140", "Blankets and travelling rugs, of synthetic fibres", "合成纤维制毯子及旅行毯"),
    GLOVES_KNITTED_SYNTHETIC("611693", "Gloves, mittens and mitts, knitted, of synthetic fibres",
            "针织合成纤维制手套"),
    MICROPHONES("851810", "Microphones and stands therefor", "麦克风及其座架"),
    ARTICLES_PLASTICS_OTHER("392690", "Other articles of plastics", "塑料制其他制品"),
    FOOTWEAR_PARTS_OTHER("640690", "Other parts of footwear; gaiters, leggings and similar articles",
            "鞋靴的其他零件；护腿及类似品"),
    TABLEWARE_KITCHENWARE_PLASTICS("392410", "Tableware and kitchenware, of plastics", "塑料制餐具及厨房用具"),
    PHYSICAL_EXERCISE_EQUIPMENT("950691",
            "Articles and equipment for general physical exercise, gymnastics or athletics",
            "一般体育锻炼、体操或田径用品及设备"),
    TOYS_WHEELED_DOLLS("950300", "Tricycles, scooters, dolls and other toys", "三轮车、踏板车、玩偶及其他玩具"),
    LOUDSPEAKERS_MULTIPLE("851822", "Multiple loudspeakers, mounted in the same enclosure", "同一箱体内的多喇叭扬声器"),
    SPECTACLE_FRAMES_PLASTICS("900311", "Frames and mountings for spectacles, of plastics", "塑料制眼镜架"),
    WOMENS_JACKETS_OTHER_MATERIALS("620439", "Women's or girls' jackets and blazers, of other textile materials",
            "其他纺织材料制女式上衣"),
    WOMENS_TROUSERS_KNITTED_SYNTHETIC("610463",
            "Women's or girls' trousers and shorts, knitted, of synthetic fibres", "针织合成纤维制女式长裤及短裤"),
    BEDDING_ARTICLES_OTHER("940490", "Other articles of bedding: quilts, cushions and pillows",
            "其他寝具：被褥、靠垫及枕头"),
    DRESSES_COTTON_WOVEN("620442", "Women's or girls' dresses, of cotton", "女式棉制连衣裙"),
    HOUSEHOLD_ARTICLES_STAINLESS_STEEL("732393",
            "Table, kitchen or other household articles, of stainless steel", "不锈钢制餐桌、厨房或其他家用器具"),
    TSHIRTS_COTTON_KNITTED("610910", "T-shirts, singlets and other vests, knitted, of cotton",
            "针织棉制T恤衫及背心"),
    GAS_FILTERING_APPARATUS("842139", "Filtering or purifying machinery and apparatus for gases",
            "气体过滤或净化设备"),
    CLOTHING_ACCESSORIES_MADE_UP("621710", "Other made up clothing accessories", "其他制成的衣着附件"),
    VEHICLE_PARTS_OTHER("870899", "Other parts and accessories for motor vehicles", "机动车辆的其他零件及附件"),
    LED_LUMINAIRES("940542",
            "Other electric luminaires and lighting fittings, light-emitting diode (LED)",
            "其他发光二极管（LED）电灯具及照明装置");

    private final String hs6;
    private final String ename;
    private final String cname;

    Hs6DeclarationText(String hs6, String ename, String cname) {
        this.hs6 = hs6;
        this.ename = ename;
        this.cname = cname;
    }

    public String hs6() {
        return hs6;
    }

    /** Texto en inglés de la partida: la base del {@code EName} del grupo. */
    public String ename() {
        return ename;
    }

    /** Texto en chino de la partida: la base del {@code CName}, que el transportista valida por ideogramas. */
    public String cname() {
        return cname;
    }

    /** La partida con ese código, si el catálogo la conoce. Se recorre {@code values()} a propósito: son 67. */
    public static Optional<Hs6DeclarationText> byCode(String hs6) {
        if (hs6 == null || hs6.isBlank()) {
            return Optional.empty();
        }
        String buscado = hs6.trim().toUpperCase(Locale.ROOT);
        for (Hs6DeclarationText partida : values()) {
            if (partida.hs6.equals(buscado)) {
                return Optional.of(partida);
            }
        }
        return Optional.empty();
    }
}
