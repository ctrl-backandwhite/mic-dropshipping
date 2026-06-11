/*
 * Plan 300k req/min — soak test del catálogo storefront.
 *
 * Mix de tráfico aproximado a la realidad del dropshipping:
 *   - 65% listings (/products con filtros + paging)
 *   - 20% PDP    (/products/{slug})
 *   - 10% categorías / suppliers (cacheables fuertes)
 *   -  5% search (q=)
 *
 * Lanzamiento:
 *   k6 run -e BASE=http://localhost:18082 -e DURATION=30m loadtests/k6-catalog-300k.js
 *
 * Para reproducir el target nominal (5000 rps sostenidos = 300k/min):
 *   k6 run --vus 500 --duration 30m -e RPS=5000 loadtests/k6-catalog-300k.js
 *
 * El runner respeta los thresholds: si p99 > 250ms o error rate > 0.1%
 * el test FAILS y se ve en el exit code (útil para CI).
 */
import http from 'k6/http'
import { check, sleep } from 'k6'
import { Trend, Rate } from 'k6/metrics'

const BASE = __ENV.BASE || 'http://localhost:18082'
const RPS  = parseInt(__ENV.RPS || '5000', 10)
const DURATION = __ENV.DURATION || '5m'

const trendListings = new Trend('catalog_listings_ms')
const trendPdp      = new Trend('catalog_pdp_ms')
const errorRate     = new Rate('errors')

// Slugs canónicos del seed. Si tu seed cambió, regenera con:
//   psql -c "SELECT slug FROM product WHERE status='ACTIVE' LIMIT 100"
const SAMPLE_SLUGS = [
  'auriculares-bluetooth-tws-x-pro-anc-offer-01001',
  'cargador-gan-65w-triple-puerto-pd30-offer-01002',
  'smartwatch-series-9-amoled-183-spo2-offer-01003',
  'powerbank-20000mah-pd20w-carga-rapida-offer-01004',
  'camara-ip-wifi-1080p-seguimiento-auto-offer-01005',
  'mini-proyector-portatil-1080p-4k-support-wifi-offer-01006',
  'teclado-mecanico-rgb-87-teclas-hot-swap-offer-01007',
  'drone-plegable-4k-gps-retorno-automatico-offer-01008',
  'cable-usb-c-100w-trenzado-2m-pd-offer-01009',
]
const SHIP_FROM = ['CN','HK','US','ES','MX']
const SORTS     = ['best_match','price_asc','price_desc','newest','sales','rating']

export const options = {
  scenarios: {
    constant_rps: {
      executor: 'constant-arrival-rate',
      rate: RPS,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.max(50, Math.floor(RPS / 10)),
      maxVUs: Math.max(200, Math.floor(RPS / 5)),
    },
  },
  thresholds: {
    'http_req_duration{name:listings}': ['p(99)<250'],
    'http_req_duration{name:pdp}': ['p(99)<200'],
    'errors': ['rate<0.001'],
  },
  // Apaga los checks que no aportan info y comprime resultado para CI.
  discardResponseBodies: true,
  summaryTrendStats: ['avg','min','med','p(95)','p(99)','max'],
}

function pick(arr) { return arr[Math.floor(Math.random() * arr.length)] }

export default function () {
  const roll = Math.random()
  if (roll < 0.65) {
    // 65% listings
    const sort = pick(SORTS)
    const ship = Math.random() < 0.3 ? `&shipFrom=${pick(SHIP_FROM)}` : ''
    const minPrice = Math.random() < 0.2 ? '&minPrice=5' : ''
    const url = `${BASE}/api/v1/storefront/products?page=0&size=24&sort=${sort}${ship}${minPrice}`
    const res = http.get(url, { tags: { name: 'listings' } })
    trendListings.add(res.timings.duration)
    check(res, { 'listings 200': r => r.status === 200 }) || errorRate.add(1)
  } else if (roll < 0.85) {
    // 20% PDP
    const slug = pick(SAMPLE_SLUGS)
    const res = http.get(`${BASE}/api/storefront/catalog/products/${slug}`, { tags: { name: 'pdp' } })
    trendPdp.add(res.timings.duration)
    check(res, { 'pdp 200|404': r => r.status === 200 || r.status === 404 }) || errorRate.add(1)
  } else if (roll < 0.95) {
    // 10% categorias / suppliers (deben ser HIT cache del CDN/Redis en prod)
    const ep = Math.random() < 0.5 ? '/api/storefront/catalog/categories' : '/api/storefront/catalog/suppliers'
    const res = http.get(`${BASE}${ep}`, { tags: { name: 'meta' } })
    check(res, { 'meta 200': r => r.status === 200 }) || errorRate.add(1)
  } else {
    // 5% search
    const q = pick(['cable','watch','drone','camara','perfume','keyboard'])
    const res = http.get(`${BASE}/api/v1/storefront/products?q=${q}&size=24`, { tags: { name: 'search' } })
    check(res, { 'search 200': r => r.status === 200 }) || errorRate.add(1)
  }
  // No hace falta sleep — el arrival-rate executor regula el throughput.
}
