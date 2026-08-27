#!/usr/bin/env bash
#
# Verificación de seguridad completa contra el stack LOCAL.
#
#   ./verificar.sh              todo
#   ./verificar.sh pentest      solo los bloques SEC-* (rápido, ~1 min)
#   ./verificar.sh estatico     SpotBugs + Find Security Bugs
#   ./verificar.sh deps         OWASP Dependency-Check (CVE en dependencias)
#   ./verificar.sh zap          OWASP ZAP autenticado contra la API
#
# Se ejecuta SOLO contra local: hay casos destructivos (borrados, reembolsos, doble gasto) que no deben
# tocar PRE. El script comprueba que apunta a localhost antes de empezar.
#
set -uo pipefail

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACK="$AQUI/../backend"
API="${API:-http://localhost:18082}"
FRONT="${FRONT:-http://localhost:3003}"
RES="$AQUI/resultados"
mkdir -p "$RES" "$AQUI/zap"

verde() { printf '\033[32m%s\033[0m\n' "$1"; }
rojo()  { printf '\033[31m%s\033[0m\n' "$1"; }
gris()  { printf '\033[90m%s\033[0m\n' "$1"; }
titulo() { printf '\n\033[1m══ %s ══\033[0m\n' "$1"; }

case "$API" in
  *localhost*|*127.0.0.1*) ;;
  *) rojo "API=$API no es local. Esta verificación no se lanza contra entornos reales."; exit 1 ;;
esac

esperar_backend() {
  for _ in $(seq 1 40); do
    [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$API/actuator/health")" = "200" ] && return 0
    sleep 3
  done
  rojo "El backend no responde en $API"; return 1
}

# ── Pentest: los bloques SEC-* ──────────────────────────────────────────────
pentest() {
  titulo "Pentest — bloques SEC"
  esperar_backend || return 1
  cd "$AQUI" || return 1
  gris "Preparando actores y sus recursos propios…"
  python3 setup_actores.py  >"$RES/setup_actores.log"  2>&1 || { rojo "fallo preparando actores"; return 1; }
  python3 setup_recursos.py >"$RES/setup_recursos.log" 2>&1 || gris "  (algún recurso no se pudo crear; los casos afectados se marcarán SIN EJECUTAR)"
  local fallos=0
  # sec_expo_infra FALTABA en esta lista: el fichero existía y había que acordarse de lanzarlo a mano,
  # así que `verificar.sh pentest` daba por bueno un pentest al que le faltaban 28 casos —los de fuga de
  # información, cabeceras, CORS y límite de peticiones—. Un verificador que omite bloques en silencio es
  # peor que no tenerlo, porque el verde que devuelve no significa lo que uno cree.
  # sec_compliance cubre la superficie del Reglamento (UE) 2023/988, que publica datos personales a
  # propósito y por tanto conviene vigilar.
  for bloque in sec_auth_autz sec_idor sec_input sec_biz sec_expo_infra sec_compliance; do
    [ -f "$AQUI/$bloque.py" ] || continue
    python3 "$AQUI/$bloque.py" || fallos=$((fallos+1))
  done
  return $fallos
}

# ── Análisis estático: SpotBugs + Find Security Bugs ────────────────────────
estatico() {
  titulo "SpotBugs + Find Security Bugs"
  cd "$BACK" || return 1
  mvn -Psecurity spotbugs:check -DskipTests >"$RES/spotbugs.log" 2>&1
  if [ -f "$BACK/target/spotbugsXml.xml" ]; then
    python3 - <<'PY'
import xml.etree.ElementTree as ET, collections, os
x = os.path.join(os.environ.get('BACK', '.'), 'target', 'spotbugsXml.xml')
r = ET.parse(x).getroot()
sev = {1: 'ALTA', 2: 'MEDIA', 3: 'BAJA'}
c = collections.Counter(sev.get(int(b.get('priority', 3)), '?') for b in r.iter('BugInstance'))
print(f"  ALTA={c.get('ALTA',0)}  MEDIA={c.get('MEDIA',0)}  BAJA={c.get('BAJA',0)}")
for b in r.iter('BugInstance'):
    if int(b.get('priority', 3)) == 1:
        cl = b.find('Class'); sl = b.find('.//SourceLine')
        print(f"    ALTA  {b.get('type'):<32} "
              f"{cl.get('classname').split('.')[-1] if cl is not None else '?'}:"
              f"{sl.get('start') if sl is not None else '?'}")
PY
    gris "  informe: backend/target/spotbugs.html"
  else
    rojo "  SpotBugs no generó informe — ver $RES/spotbugs.log"
  fi
}

# ── Dependencias: CVE conocidos ─────────────────────────────────────────────
deps() {
  titulo "OWASP Dependency-Check"
  if [ -z "${NVD_API_KEY:-}" ]; then
    gris "  Sin NVD_API_KEY: la primera descarga del catálogo puede tardar horas o cortarse."
    gris "  Clave gratuita en https://nvd.nist.gov/developers/request-an-api-key"
  fi
  cd "$BACK" || return 1
  mvn -Psecurity dependency-check:check -DskipTests >"$RES/dependency-check.log" 2>&1
  local salida=$?
  if [ -f "$BACK/target/dependency-check-report.json" ]; then
    python3 - <<'PY'
import json, os, collections
p = os.path.join(os.environ.get('BACK', '.'), 'target', 'dependency-check-report.json')
d = json.load(open(p))
c = collections.Counter()
detalle = []
for dep in d.get('dependencies', []):
    for v in dep.get('vulnerabilities', []) or []:
        sev = (v.get('severity') or '?').upper()
        c[sev] += 1
        if sev in ('CRITICAL', 'HIGH'):
            detalle.append((sev, v.get('name'), dep.get('fileName')))
print('  ' + '  '.join(f'{k}={v}' for k, v in sorted(c.items())) if c else '  sin vulnerabilidades')
for sev, cve, fichero in sorted(set(detalle))[:20]:
    print(f'    {sev:<9} {cve:<20} {fichero}')
PY
    gris "  informe: backend/target/dependency-check-report.html"
  else
    rojo "  sin informe — ver $RES/dependency-check.log"
  fi
  return $salida
}

# ── ZAP: escaneo dinámico AUTENTICADO ───────────────────────────────────────
# Sin token y sin semillas, ZAP solo alcanza las tres páginas públicas y termina en verde sin haber
# probado nada. Se le inyecta el Authorization de un actor real y se le dan las rutas sacadas del código.
zap() {
  titulo "OWASP ZAP (autenticado)"
  esperar_backend || return 1
  cd "$AQUI" || return 1

  [ -f actores.json ] || { gris "  no hay actores; ejecutando la preparación…"; python3 setup_actores.py >/dev/null 2>&1; }
  local token
  token=$(python3 -c "import json;print(json.load(open('actores.json'))['u1']['token'])" 2>/dev/null)
  [ -z "$token" ] && { rojo "  sin token de usuario: el escaneo no cubriría nada"; return 1; }

  python3 generar_urls_api.py "$API" >/dev/null 2>&1
  local n; n=$(grep -c "" zap/urls-api.txt 2>/dev/null || echo 0)
  gris "  $n URLs de la API como semilla, con sesión de usuario"

  docker run --rm --network host -v "$AQUI/zap":/zap/wrk:rw zaproxy/zap-stable \
    zap-baseline.py -t "$API" -r zap-api.html -J zap-api.json -I -m 5 \
    -z "-config replacer.full_list(0).description=auth \
        -config replacer.full_list(0).enabled=true \
        -config replacer.full_list(0).matchtype=REQ_HEADER \
        -config replacer.full_list(0).matchstr=Authorization \
        -config replacer.full_list(0).regex=false \
        -config replacer.full_list(0).replacement=\"Bearer $token\"" \
    >"$RES/zap-api.log" 2>&1
  grep -E "^(FAIL|WARN)-NEW|^FAIL-NEW:" "$RES/zap-api.log" | tail -20
  gris "  informe: certificacion/zap/zap-api.html"

  docker run --rm --network host -v "$AQUI/zap":/zap/wrk:rw zaproxy/zap-stable \
    zap-baseline.py -t "$FRONT" -r zap-front.html -J zap-front.json -I -m 3 \
    >"$RES/zap-front.log" 2>&1
  grep -E "^FAIL-NEW:" "$RES/zap-front.log" | tail -5
  gris "  informe: certificacion/zap/zap-front.html"
}

case "${1:-todo}" in
  pentest)  pentest ;;
  estatico) BACK="$BACK" estatico ;;
  deps)     BACK="$BACK" deps ;;
  zap)      zap ;;
  todo)     pentest; BACK="$BACK" estatico; zap; BACK="$BACK" deps ;;
  *)        echo "uso: $0 [todo|pentest|estatico|deps|zap]"; exit 1 ;;
esac

titulo "Resultados en $RES"
