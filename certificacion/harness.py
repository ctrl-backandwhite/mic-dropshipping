#!/usr/bin/env python3
"""Utilidades comunes de la certificación: cliente HTTP, CAPTCHA y alta de actores.

No contiene casos de prueba: solo lo necesario para tener actores con token y recursos propios,
que es lo que permite cruzar los casos de acceso a recursos ajenos (IDOR).
"""
import base64
import hashlib
import json
import uuid
import time
import urllib.error
import urllib.parse
import urllib.request

API = "http://localhost:18082"
DB = ["docker", "exec", "-i", "nexadrop-postgres", "psql", "-U", "nexadrop", "-d", "nexadrop", "-P", "pager=off",
      "-t", "-A", "-c"]


class Res:
    """Respuesta simplificada: código, cuerpo crudo, JSON (si lo es) y cabeceras."""

    def __init__(self, status, body, headers):
        self.status = status
        self.body = body
        self.headers = headers

    @property
    def json(self):
        try:
            return json.loads(self.body)
        except Exception:  # noqa: BLE001
            return None

    def __repr__(self):
        return f"<{self.status} {self.body[:120]!r}>"


class _SinRedirecciones(urllib.request.HTTPRedirectHandler):
    """Deja pasar el 3xx tal cual en vez de seguirlo."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


_ip = [0]


_RUTAS_CON_DINERO = ("/api/me/orders/checkout", "/payment-intent", "/pay-saved-card",
                     "/api/me/wallet/recharge")


def _mueve_dinero(path):
    """¿La ruta es uno de los endpoints que exigen clave de intento? (no sus sub-rutas /confirm)."""
    if path.endswith("/confirm") or path.endswith("/confirm-mock"):
        return False
    return any(r in path for r in _RUTAS_CON_DINERO)


def call(method, path, token=None, body=None, headers=None, raw_body=None, timeout=60,
         seguir_redirecciones=True):
    """Petición HTTP.

    OJO con `seguir_redirecciones`: por defecto urllib sigue los 3xx, y eso ENMASCARA resultados. Un
    /actuator/env que responde 302 hacia /login parecía un 200 abierto de par en par, porque el cliente
    acababa en la página de login. Para comprobar si algo está cerrado, pásalo a False y mira el código
    real.
    """
    url = path if path.startswith("http") else API + path
    data = None
    hdrs = {"Accept": "application/json"}
    if raw_body is not None:
        data = raw_body.encode() if isinstance(raw_body, str) else raw_body
        hdrs["Content-Type"] = "application/json"
    elif body is not None:
        data = json.dumps(body).encode()
        hdrs["Content-Type"] = "application/json"
    if token:
        hdrs["Authorization"] = f"Bearer {token}"
    # Cada petición sale de una IP distinta. El rate limiter cuenta por IP y es correcto que lo haga, pero
    # una batería de pentest dispara cientos de intentos de login seguidos: sin rotar, a partir del enésimo
    # todo responde 429 y los casos posteriores se dan por FALLADOS sin haberse ejecutado — un límite que
    # funciona se leía como tres agujeros de autorización. Se rota aquí, en el único punto por el que pasan
    # todas las llamadas, para que ningún bloque futuro tenga que acordarse.
    _ip[0] += 1
    hdrs.setdefault("X-Forwarded-For", "198.18.%d.%d" % (_ip[0] // 250 % 250, _ip[0] % 250 + 1))
    # Los endpoints que mueven dinero EXIGEN Idempotency-Key: la clave identifica el intento, y sin ella el
    # servidor responde 400 en vez de abrir un segundo cobro. Se pone aquí, en el único punto por el que
    # pasan todas las llamadas, para que ningún bloque tenga que acordarse — igual que la rotación de IP.
    #
    # Una clave NUEVA por llamada a propósito: cada petición del pentest es un intento distinto, que es lo
    # que hace que los casos de doble gasto midan la defensa del saldo y no la deduplicación. Las pruebas
    # que quieren un REENVÍO del mismo intento pasan su propia clave y esta línea las respeta.
    if method == "POST" and _mueve_dinero(path):
        hdrs.setdefault("Idempotency-Key", str(uuid.uuid4()))
    if headers:
        hdrs.update(headers)
    req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
    abrir = (urllib.request.urlopen if seguir_redirecciones
             else urllib.request.build_opener(_SinRedirecciones).open)
    try:
        with abrir(req, timeout=timeout) as r:
            return Res(r.status, r.read().decode("utf-8", "replace"), dict(r.headers))
    except urllib.error.HTTPError as e:
        return Res(e.code, e.read().decode("utf-8", "replace"), dict(e.headers))
    except Exception as e:  # noqa: BLE001
        return Res(0, f"ERROR-TRANSPORTE: {e}", {})


def solve_captcha():
    """Resuelve el proof-of-work de ALTCHA y devuelve la cabecera X-Altcha lista para usar."""
    r = call("GET", "/api/captcha/challenge")
    if r.status != 200:
        return None
    c = r.json
    salt, target, maxnum = c["salt"], c["challenge"], int(c.get("maxnumber", 100000))
    for n in range(maxnum + 1):
        if hashlib.sha256(f"{salt}{n}".encode()).hexdigest() == target:
            payload = {"algorithm": c["algorithm"], "challenge": target, "number": n, "salt": salt,
                       "signature": c["signature"]}
            return base64.b64encode(json.dumps(payload).encode()).decode()
    return None


def sql(query):
    """Consulta directa a la base — se usa para leer tokens de activación, no para montar datos."""
    import subprocess
    out = subprocess.run(DB + [query], capture_output=True, text=True, timeout=60)
    return out.stdout.strip()


_ip_registro = [0]


def register(email, password, extra=None):
    """Registro real por la API pública, resolviendo el CAPTCHA como haría el navegador.

    Cada registro sale de una IP recién estrenada. La política `auth.register` cuenta por IP con una
    ventana de UNA HORA, así que preparar actores dos veces seguidas —algo que pasa en cuanto se repite
    una certificación— devolvía 429 al segundo usuario y dejaba el pentest entero sin ejecutar. El límite
    funciona: quien lo comprueba es SEC-INFRA-04, con su propia ráfaga. Aquí solo estorbaba.
    """
    # La aceptación es OBLIGATORIA desde que el registro dejó de admitir altas sin constancia. El campo se
    # llama `acceptedTerms` (no `acceptTerms`, que era el nombre del formulario y aquí no se validaba
    # nunca), y hay que mandar también la versión: sin ella el alta responde 400 y la certificación entera
    # se queda sin actores.
    body = {"email": email, "password": password, "firstName": "Cert", "lastName": "Test",
            "acceptedTerms": True, "acceptedTermsVersion": "2026-08-15", "country": "ES"}
    if extra:
        body.update(extra)
    cap = solve_captcha()
    _ip_registro[0] += 1
    # Dos octetos, no uno. Con 250 direcciones la ventana de UNA HORA de `auth.register` se agotaba a la
    # tercera certificación del día y el registro empezaba a devolver 429: el flujo entero se quedaba sin
    # ejecutar por falta de direcciones, no por un fallo de la aplicación.
    n = _ip_registro[0] + int(time.time()) % 60000
    headers = {"X-Forwarded-For": f"198.51.{n // 250 % 250}.{n % 250 + 1}"}
    if cap:
        headers["X-Altcha"] = cap
    return call("POST", "/api/auth/register", body=body, headers=headers)


def activate(email):
    """Activa la cuenta con el token que se le generó (lo que el usuario recibiría por correo)."""
    code = sql(f"SELECT activation_code FROM users WHERE email='{email}'")
    if not code:
        return None
    return call("POST", "/api/auth/activate", body={"code": code})


def login(email, password, otp=None):
    body = {"email": email, "password": password}
    if otp:
        body["otp"] = otp
    return call("POST", "/api/auth/login", body=body)


def token_of(email, password):
    r = login(email, password)
    return (r.json or {}).get("token") if r.status == 200 else None
