"""O logcat do sistema (`logcat-sistema*.txt`, formato `-v threadtime`) que o `coletar_do_celular.sh` traz, lido para o `ler_registro.py`.

    MM-DD HH:MM:SS.mmm  PID  TID L TAG: texto

Duas coisas só, as que o registro do REau não vê: o Android Auto (`com.google.android.projection.gearhead`) que travou com
`FATAL EXCEPTION`, com o intervalo desde o `byebye` que o REau mandou, e os travamentos de quadros do próprio REau (`Davey!` e
`Skipped N frames` com N de 100 para cima). O arquivo tem dado pessoal (nome do dono, e-mails, o Wi-Fi de casa): aqui só saem a
hora, o pacote e o nome da classe da exceção, nunca a mensagem dela nem o resto das linhas (a mensagem é texto livre).
"""
import re
from collections import namedtuple
from datetime import datetime, timedelta
from pathlib import Path

THREADTIME = re.compile(r"^(\d\d)-(\d\d) (\d\d:\d\d:\d\d\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+([^:]*?)\s*: (.*)$")
GEARHEAD = "com.google.android.projection.gearhead"
PROCESSO = re.compile(r"^Process: ([\w.]+)(?::(\w+))?, PID: (\d+)")
# The app's process: `am_cpu` and `am_proc_start` carry `[pid,uid,package` / `[0,pid,uid,package`; the system's `Start proc` too.
PID_DO_APP = [re.compile(r"\[(?:0,)?(\d+),\d+,com\.reau\.app(?:\.lite)?[,\]]"), re.compile(r"Start proc (\d+):com\.reau\.app(?:\.lite)?[/ ]")]
DAVEY = re.compile(r"Davey! duration=(\d+)ms")
PULADOS = re.compile(r"Skipped (\d+) frames")
PULADOS_MINIMO = 100
MASCARADO = ".mascarado"  # `logcat-sistema.mascarado.txt`: a cópia com o nome do painel trocado
BYE_ATE_S = 60.0  # a crash this soon after our byebye is told as the cause being close; later, only the time
Entrada = namedtuple("Entrada", "mes dia hora pid tid nivel tag texto")
# O formato `-v threadtime` não diz de que buffer é a linha; as etiquetas dizem. `main` é o dos apps e dos serviços (o `wpa_supplicant`, o Android Auto `GH.`/`CAR.`,
# o `WifiNetworkFactory`, o codec); `system` é o do `system_server` (o `ActivityManager`, o `AppOps`). Na coleta de 08/11/2026 o `system` ia de 08:39 e o `main` só de 18:24:53.
ETIQUETAS_DO_BUFFER = {
    "main": ("wpa_supplicant", "WifiNetworkFactory", "GH.", "CAR.", "CCodec", "QC2C2DEngine", "FusedLocation", "netd", "Choreographer"),
    "system": ("ActivityManager", "AppOps", "WindowManager", "ThermalManagerService"),
}
# O HAL térmico morto de alguns celulares (`ThermalHAL not initialized properly`) escreve centenas de milhares de linhas e expulsa o resto do buffer: não conta como cobertura.
ENCHENTE_TERMICA = ("ThermalHalWrapper", "android.hardware.thermal")


def e_enchente(e):
    return e.tag.startswith(ENCHENTE_TERMICA)


def etiqueta_do_buffer(e, buffer):
    """A entrada é de uma etiqueta que só o buffer `main` ou `system` carrega (ver `ETIQUETAS_DO_BUFFER`); falso para as outras e para a enchente térmica."""
    return not e_enchente(e) and e.tag.startswith(ETIQUETAS_DO_BUFFER[buffer])


def arquivos(caminhos):
    """Os `logcat-sistema*.txt` dos caminhos: arquivos soltos, ou pastas (e a pasta de cima quando a dada é a `diag`).

    A coleta guarda o logcat duas vezes, `logcat-sistema.txt` e `logcat-sistema.mascarado.txt` (o mesmo texto com o nome do painel trocado): lido os dois, cada
    evento contaria em dobro (o NPE do Android Auto, 03/11/2026). Fica um só: o não mascarado; o mascarado só entra quando não há o outro com o mesmo nome base."""
    achados = []
    for c in map(Path, caminhos):
        if c.is_dir():
            pastas = [c] + ([c.parent] if c.name == "diag" else [])
            achados += [a for p in pastas for a in sorted(p.glob("logcat-sistema*.txt"))]
        elif c.name.startswith("logcat-sistema"):
            achados.append(c)
    unicos = list(dict.fromkeys(a.resolve() for a in achados))
    inteiros = {a.name for a in unicos if MASCARADO not in a.name}
    return [a for a in unicos if MASCARADO not in a.name or a.name.replace(MASCARADO, "") not in inteiros]


def arquivos_do_app(caminhos):
    """Os `logcat-*.txt` do próprio app (a pasta `diag` do aparelho): na pasta dada, na `diag` dentro dela, ou o arquivo solto; nunca o do sistema."""
    saida = []
    for c in map(Path, caminhos):
        if c.is_dir():
            saida += [a for p in (c, c / "diag") if p.is_dir() for a in sorted(p.glob("logcat-*.txt")) if not a.name.startswith("logcat-sistema")]
        elif c.name.startswith("logcat-") and not c.name.startswith("logcat-sistema"):
            saida.append(c)
    return list(dict.fromkeys(saida))


def ler(caminhos, so_do_app=False):
    """As entradas `-v threadtime` dos `logcat-sistema*.txt`, ou, com `so_do_app`, dos `logcat-*.txt` do app."""
    entradas = []
    for a in (arquivos_do_app(caminhos) if so_do_app else arquivos(caminhos)):
        for bruta in a.read_text(encoding="utf-8", errors="replace").splitlines():
            m = THREADTIME.match(bruta)
            if m:
                entradas.append(Entrada(int(m.group(1)), int(m.group(2)), m.group(3), int(m.group(4)), int(m.group(5)),
                                        m.group(6), m.group(7), m.group(8)))
    return entradas


def _instante(e, ano, byebyes):
    """O instante da entrada. O logcat não traz o ano: vale o `ano` dos registros, ou o vizinho que cai mais perto de um byebye (virada
    de dezembro para janeiro); um dia que o ano não tem (29/02) fica sem instante."""
    h, m, s = e.hora.split(":")
    candidatos = []
    for a in (ano, ano - 1, ano + 1):
        try:
            candidatos.append(datetime(a, e.mes, e.dia, int(h), int(m), int(s[:2])) + timedelta(milliseconds=int(e.hora[-3:])))
        except ValueError:
            continue
    if candidatos and byebyes:
        return min(candidatos, key=lambda c: min(abs((c - b).total_seconds()) for b in byebyes))
    return candidatos[0] if candidatos else None


def _hora(e):
    return e.hora[:8]


def travadas_do_android_auto(entradas, byebyes, ano):
    """`11:45:19  o Android Auto (gearhead:projection) travou: NullPointerException … (1,2 s depois do byebye do REau)` para cada
    `FATAL EXCEPTION` cujo processo é o do Android Auto. `byebyes` são os instantes (datetime) em que o REau mandou o byebye."""
    saida = []
    for i, e in enumerate(entradas):
        if e.tag != "AndroidRuntime" or not e.texto.startswith("FATAL EXCEPTION"):
            continue
        seguintes = [y for y in entradas[i + 1:i + 6] if y.pid == e.pid and y.tag == "AndroidRuntime"]
        proc = next((m for y in seguintes for m in [PROCESSO.match(y.texto)] if m), None)
        if proc is None or proc.group(1) != GEARHEAD:
            continue
        mensagem = next((y.texto for y in seguintes if not PROCESSO.match(y.texto) and not y.texto.startswith("\t")), "")
        excecao = mensagem.split(":")[0].strip()  # the class only: the message is free text
        nome = f"gearhead:{proc.group(2)}" if proc.group(2) else "gearhead"
        quando = _instante(e, ano, byebyes)
        antes = [(quando - b).total_seconds() for b in byebyes if quando and 0 <= (quando - b).total_seconds() <= BYE_ATE_S]
        depois = f" ({min(antes):.1f} s depois do byebye do REau)".replace(".", ",") if antes else " (sem byebye do REau nos 60 s antes)"
        saida.append(f"{_hora(e)}: o Android Auto ({nome}) travou — {excecao[:100] or 'FATAL EXCEPTION'}{depois}")
    return saida


def pids_do_app(entradas):
    return {int(m.group(1)) for e in entradas for rx in PID_DO_APP for m in [rx.search(e.texto)] if m}


def travadas_do_app(entradas):
    """`Davey!` (um quadro de 700 ms ou mais) e `Skipped N frames` (N ≥ 100) do processo do REau: quantas vezes e a pior."""
    pids = pids_do_app(entradas)
    daveys = [(int(m.group(1)), e) for e in entradas if e.pid in pids for m in [DAVEY.search(e.texto)] if m]
    pulados = [(int(m.group(1)), e) for e in entradas if e.pid in pids and e.tag == "Choreographer"
               for m in [PULADOS.search(e.texto)] if m and int(m.group(1)) >= PULADOS_MINIMO]
    saida = []
    if daveys:
        ms, e = max(daveys, key=lambda d: d[0])
        saida.append(f"{_hora(e)}: o app travou quadros ({len(daveys)}× Davey!, o pior de {ms} ms)")
    if pulados:
        n, e = max(pulados, key=lambda d: d[0])
        saida.append(f"{_hora(e)}: o app pulou quadros ({len(pulados)}× com {PULADOS_MINIMO} ou mais, o pior com {n})")
    return saida


POR_EVENTO_MAX = 30
TOLERANCIA_FIM_S = 1.0  # a frame is logged when it ends: a stall that began in the walk is told a moment after it


def travadas_do_app_por_evento(entradas, ano, caminhadas):
    """Cada `Davey!` e cada `Skipped N frames` (N ≥ 100) do logcat do próprio app (todas as linhas são dele), com a hora e, se cai
    numa caminhada do servidor, dizendo qual. `caminhadas`: (modo, início, fim) de cada caminhada, em datetime. As das caminhadas
    primeiro e, no total, até `POR_EVENTO_MAX`, e uma linha com quantas mais houve."""
    eventos = []
    for e in entradas:
        davey = DAVEY.search(e.texto)
        pulados = PULADOS.search(e.texto) if e.tag == "Choreographer" else None
        if davey:
            eventos.append((e, f"Davey! de {int(davey.group(1))} ms"))
        elif pulados and int(pulados.group(1)) >= PULADOS_MINIMO:
            eventos.append((e, f"{int(pulados.group(1))} quadros pulados"))
    contados = []
    for e, o_que in eventos:
        quando = _instante(e, ano, [])
        fase = ""
        for modo, inicio, fim in caminhadas:
            if quando and inicio <= quando <= fim + timedelta(seconds=TOLERANCIA_FIM_S):
                fase = f" (durante a caminhada de {'iniciar' if modo == 'start' else 'parar' if modo == 'stop' else 'visitar'} o servidor)"
                break
        contados.append((e, f"{e.dia:02d}/{e.mes:02d} {_hora(e)}: o app travou a tela — {o_que}{fase}", bool(fase)))
    # The ones that fell in a walk first (that is what the report asks), then the others, each in the order of the file.
    primeiros = sorted(contados, key=lambda c: not c[2])[:POR_EVENTO_MAX]
    saida = [linha for _, linha, _ in sorted(primeiros, key=lambda c: contados.index(c))]
    if len(eventos) > POR_EVENTO_MAX:
        saida.append(f"(mais {len(eventos) - POR_EVENTO_MAX} travadas do app no logcat; as {POR_EVENTO_MAX} listadas são as das caminhadas e, depois, as primeiras)")
    return saida


# O que o SISTEMA diz do Wi-Fi do painel na hora de uma queda (plano 25). Só o que não identifica ninguém: do `wpa_supplicant`, o nome do evento
# e os campos `reason`, `status_code`, `locally_generated`, `auth_type` (nunca o BSSID nem o SSID); das outras fontes, só as linhas que citam o
# Wi-Fi do painel (`RE_xxxx_nnnnnn`), com o nome e os endereços MAC escondidos e o texto cortado. Outras redes (a de casa) nunca saem.
WIFI_ANTES_S = 30.0
WIFI_DEPOIS_S = 10.0
WIFI_POR_QUEDA = 8
WIFI_TAGS = ("wpa_supplicant", "WifiService", "WifiClientModeImpl", "ClientModeImpl", "WifiConnectivityManager", "WifiNetworkFactory",
             "WifiNative", "WifiHealthMonitor", "ConnectivityService", "NetworkMonitor", "NetworkAgent", "netd")
EVENTO_WPA = re.compile(
    r"CTRL-EVENT-(?:DISCONNECTED|CONNECTED|ASSOC-REJECT|AUTH-REJECT|SSID-TEMP-DISABLED|SSID-REENABLED|NETWORK-NOT-FOUND|TERMINATING|CHANNEL-SWITCH)"
)  # not the signal changes, one every few seconds
CAMPO_WPA = re.compile(r"\b(reason|status_code|locally_generated|auth_type)=(\S+)")
DASH = re.compile(r"\bRE_[A-Za-z0-9_]{4,}")
MAC = re.compile(r"\b(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\b")
WIFI_TEXTO_MAX = 140


def _resumo_wifi(e):
    """A linha do sistema como pode ser contada, ou None se não é do Wi-Fi do painel nem um evento do `wpa_supplicant`."""
    if e.tag == "wpa_supplicant":
        evento = EVENTO_WPA.search(e.texto)
        if not evento:
            return None
        campos = " ".join(f"{k}={v}" for k, v in CAMPO_WPA.findall(e.texto))
        return f"wpa_supplicant {evento.group(0)}" + (f" {campos}" if campos else "")
    if e.tag in WIFI_TAGS and DASH.search(e.texto):
        texto = MAC.sub("**:**:**:**:**:**", DASH.sub("RE_****_******", e.texto)).strip()
        return f"{e.tag} {texto[:WIFI_TEXTO_MAX]}"
    return None


def wifi_nas_quedas(entradas, quedas, ano, da_moto=()):
    """Para cada queda do Wi-Fi do painel (`quedas`: instantes em datetime, do registro do app), o que o sistema disse nos `WIFI_ANTES_S` s antes e
    nos `WIFI_DEPOIS_S` s depois: `10:06:55: o Wi-Fi do painel caiu — o sistema disse: 10:06:54.9 wpa_supplicant CTRL-EVENT-DISCONNECTED reason=3 … `.
    Até `WIFI_POR_QUEDA` linhas por queda; uma queda sem nada no logcat não gera linha (o logcat guarda pouca história). `da_moto` são as quedas (instantes) que são o painel
    desligando com a moto: a linha diz `(moto desligada)` para o leitor não a contar como defeito."""
    saida = []
    com_instante = [(e, _instante(e, ano, [])) for e in entradas if e.tag in WIFI_TAGS]
    for queda in sorted(set(quedas)):
        achados = []
        for e, quando in com_instante:
            if quando is None or not -WIFI_ANTES_S <= (quando - queda).total_seconds() <= WIFI_DEPOIS_S:
                continue
            resumo = _resumo_wifi(e)
            if resumo:
                achados.append(f"{e.hora[:10]} {resumo}")
        if achados:
            mais = f" (+{len(achados) - WIFI_POR_QUEDA} linhas)" if len(achados) > WIFI_POR_QUEDA else ""
            moto = " (moto desligada)" if queda in da_moto else ""
            saida.append(f"{queda:%H:%M:%S}: o Wi-Fi do painel caiu{moto} — o sistema disse: " + " | ".join(achados[:WIFI_POR_QUEDA]) + mais)
    return saida
