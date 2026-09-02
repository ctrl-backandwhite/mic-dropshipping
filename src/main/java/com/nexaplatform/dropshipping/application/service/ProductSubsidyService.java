package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Cuánto dinero hay para abaratarle al cliente el porte y el arancel, y de dónde sale.
 *
 * <p>Sale de dos importes que el admin asigna a cada producto: {@code shipping_user_cny} y
 * {@code duty_user_cny}. Hasta el 1-sep-2026 la subvención se calculaba a partir del margen —se
 * devolvía el porte que no se repetía al pedir la segunda unidad, y la ganancia por encima de un suelo
 * de cinco euros en la Unión Europea—, de modo que el envío y el arancel se financiaban con la
 * ganancia del pedido. Ahora no: se cubre exactamente lo que se ha asignado, y el margen no responde
 * de ello.
 *
 * <p><b>Una vez por producto, no por unidad.</b> El proveedor manda un solo bulto tenga el cliente una
 * unidad o cinco, y da igual que sean de tallas o colores distintos: sigue siendo un envío. Contar el
 * importe por cada unidad multiplicaría una cobertura que en la realidad no se multiplica.
 *
 * <p><b>Las dos bolsas son estancas.</b> Lo que sobre de la del porte no cubre arancel, ni al revés.
 * El reparto en cascada tenía sentido cuando la bolsa era una sola y la calculaba el sistema; con dos
 * importes asignados a mano, que uno se cuele en el concepto del otro haría impredecible lo que ve el
 * cliente a partir de lo que se teclea.
 */
@Service
@RequiredArgsConstructor
public class ProductSubsidyService {

    private final CurrencyRateService currencyService;

    /** Las dos bolsas, en céntimos de dólar. Nunca negativas. */
    public record Bags(int shippingCents, int dutyCents) {

        /** Sin subvención: el atajo de los llamantes y las pruebas que no la usan. */
        public static final Bags NONE = new Bags(0, 0);
    }

    /**
     * Suma las bolsas de los productos del carrito, contando cada producto una sola vez.
     *
     * @param productos los productos de las líneas; admite repetidos y nulos, que se ignoran
     * @return las dos bolsas en céntimos de dólar; nunca {@code null}
     */
    public Bags bagsFor(List<ProductEntity> productos) {
        if (productos == null || productos.isEmpty()) {
            return Bags.NONE;
        }
        Set<UUID> vistos = new HashSet<>();
        BigDecimal envioUsd = BigDecimal.ZERO;
        BigDecimal arancelUsd = BigDecimal.ZERO;
        for (ProductEntity p : productos) {
            if (p == null || p.getId() == null || !vistos.add(p.getId())) {
                continue;
            }
            String sourceCurrency = p.getCurrency() != null ? p.getCurrency() : "CNY";
            envioUsd = envioUsd.add(aUsd(p.getShippingUserCny(), sourceCurrency));
            arancelUsd = arancelUsd.add(aUsd(p.getDutyUserCny(), sourceCurrency));
        }
        return new Bags(centimos(envioUsd), centimos(arancelUsd));
    }

    /**
     * Un importe en la moneda del producto, a dólares.
     *
     * <p>Nulo, cero o negativo cuentan como cero: un importe negativo no es una subvención al revés
     * —que le cobraría al cliente MÁS envío del cotizado—, es un dato mal metido.
     */
    private BigDecimal aUsd(BigDecimal valor, String sourceCurrency) {
        if (valor == null || valor.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal usd = currencyService.toUsd(valor, sourceCurrency);
        return usd != null ? usd : BigDecimal.ZERO;
    }

    /** Un importe en dólares a céntimos, con el redondeo de siempre. */
    private static int centimos(BigDecimal usd) {
        return usd.setScale(2, RoundingMode.HALF_UP).movePointRight(2).intValueExact();
    }
}
