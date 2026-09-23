package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Firma de las peticiones a la YunExpress Open Platform.
 *
 * <p>Los vectores esperados NO están calculados por nosotros: se obtuvieron de la pantalla
 * <i>签名验证</i> del console de YunExpress con la clave de sandbox. Si el algoritmo dejara de coincidir
 * (orden de los campos, codificación, hash), estos tests fallan antes de que el gateway devuelva 401.
 */
class YunExpressSignatureTest {

    /** Clave (AK) de la aplicación de sandbox con la que se generaron los vectores en el console. */
    private static final String SANDBOX_SECRET = "90326310763c46378344f2747336adab";

    @Test
    void reproduceElVectorDelConsole() {
        assertThat(YunExpressClient.sign("abc", SANDBOX_SECRET))
                .isEqualTo("yZYBSf5/L7WjCTUBTBf4a2KmBPrg8qiQjnMyqLZ592M=");
    }

    @Test
    void firmaEnUtf8ConAcentosYCaracteresChinos() {
        // El contenido se firma en UTF-8: un JSON con acentos o chino (los nombres declarados lo llevan)
        // debe dar exactamente la misma firma que devuelve el console.
        String content = "{\"orderNo\":\"NX-1\",\"country\":\"ES\",\"ñá中\":\"1\"}";

        assertThat(YunExpressClient.sign(content, SANDBOX_SECRET))
                .isEqualTo("40cCa3EFZWcnd0oKlSjjEK2rrk3nEh3gssq3AJWVgyU=");
    }

    @Test
    void contenidoFirmadoOrdenaLosCamposAlfabeticamente() {
        // Formato documentado: body=..&date=..&method=..&uri=..
        assertThat(YunExpressClient.signatureContent("POST", "/api/test", "{}", "1651049235123"))
                .isEqualTo("body={}&date=1651049235123&method=POST&uri=/api/test");
    }

    @Test
    void sinCuerpoNoSeIncluyeElCampoBody() {
        assertThat(YunExpressClient.signatureContent("GET", "/api/test", null, "1651049235123"))
                .isEqualTo("date=1651049235123&method=GET&uri=/api/test");
        assertThat(YunExpressClient.signatureContent("GET", "/api/test", "", "1651049235123"))
                .isEqualTo("date=1651049235123&method=GET&uri=/api/test");
    }

    @Test
    void distingueUnErrorDeNegocioDeUnFalloDelGateway() {
        // YunExpress responde 400 + {"success":false,...} a situaciones NORMALES —p.ej. una guía recién
        // creada que aún no se ha propagado al servicio de trazabilidad—. Tratarlas como caída de red
        // tumbaría el sondeo de seguimiento, así que el cuerpo se devuelve y lo interpreta cada operación.
        assertThat(YunExpressClient
                .isBusinessError("{\"success\":false,\"code\":\"02041002\",\"msg\":\"The order does not exist\"}"))
                .isTrue();
        assertThat(YunExpressClient.isBusinessError("{\"success\":true,\"result\":{}}")).isFalse();
        assertThat(YunExpressClient.isBusinessError("<html>502 Bad Gateway</html>")).isFalse();
        assertThat(YunExpressClient.isBusinessError("{\"code\":\"0200404001\"}")).isFalse();
        assertThat(YunExpressClient.isBusinessError("")).isFalse();
        assertThat(YunExpressClient.isBusinessError(null)).isFalse();
    }

    @Test
    void queryStringSeCodificaYOmiteValoresVacios() {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("country_code", "ES");
        query.put("weight", "0.500");
        query.put("product_group_code", "");

        assertThat(YunExpressClient.encodeQuery(query)).isEqualTo("?country_code=ES&weight=0.500");
        assertThat(YunExpressClient.encodeQuery(Map.of())).isEmpty();
    }
}
