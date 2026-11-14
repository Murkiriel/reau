"""O `dumpsys wifi` (`sistema/dumpsys-wifi.txt`) que o `coletar_do_celular.sh` traz, lido para o `ler_registro.py`.

Duas coisas, as que o registro do REau não vê e o logcat do sistema (que guarda menos de 1 h) já perdeu:

- `mConnectionEvents` (as últimas ~20 tentativas de união do celular a uma rede): quando começou, quanto levou, se deu certo, quem pediu
  (`NOMINATOR_SPECIFIER` é um app com o `WifiNetworkSpecifier`, o REau ou outro: quem é vem do `uid`, que o dumpsys traz em cada união; `NOMINATOR_SUGGESTION` é a sugestão de rede que o sistema usa sozinho, com o REau fechado),
  o sinal e o canal. Só as do Wi-Fi do painel (o nome da rede começa com `RE_`) saem daqui.
- `StaEventList`: os `NETWORK_DISCONNECTION_EVENT` com o motivo (`reason=3:DEAUTH_LEAVING`), quem derrubou (`local_gen=true|false`) e o último sinal e canal.

O arquivo guarda as redes de casa e do trabalho do dono sem máscara, com os endereços das redes (BSSID): **nada disso sai daqui**. O leitor descarta o nome e o
BSSID na leitura (só fica se a rede é a do painel) e o que se mostra é hora, duração, motivo, sinal, canal e quem pediu. O `dumpsys-wifi.mascarado.txt` só é lido
quando não há o `dumpsys-wifi.txt` (o mascarado só troca o nome do painel; o resultado é o mesmo).

Os horários não trazem o ano (`11-3 7:45:2.909`; os milissegundos são um inteiro, sem zeros à esquerda): o ano vem do dia dos registros do REau.
"""
import re
from collections import namedtuple
from datetime import datetime, timedelta
from pathlib import Path

NOME = "dumpsys-wifi"
# `startTime=11-3 7:45:2.909, SSID="…", BSSID=…, durationMillis=689, …`: o nome só serve para saber se a rede é a do painel e não é guardado.
EVENTO_UNIAO = re.compile(r"^startTime=(\d+)-(\d+) (\d+):(\d+):(\d+)\.(\d+), SSID=\"([^\"]*)\"")
CAMPO = re.compile(r"\b(durationMillis|connectionResult|signalStrength|mChannelInfo|connectionNominator|uid)=([^\s,]+)")
# `11-03 18:46:15.118 NETWORK_DISCONNECTION_EVENT local_gen=false reason=-1:UNSPECIFIED lastRssi=-54 lastFreq=2452 …`
DESCONEXAO = re.compile(
    r"^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d{3}) NETWORK_DISCONNECTION_EVENT\b(.*)$"
)
CAMPO_DESCONEXAO = re.compile(r"\b(local_gen|reason|lastRssi|lastFreq)=(\S+)")
PAINEL = "RE_"
# Quem é cada `uid`: o dumpsys do Wi-Fi diz o pacote quando uma rede é criada ou removida (`… uid=10223 name=com.exemplo.outroapp`) e o dumpsys do conectividade
# quando um app pede uma rede (`RequestorUid: 10185 RequestorPkg: com.android.systemui`).
NOME_DO_UID_WIFI = re.compile(r"\buid=(\d+) name=([\w.]+)")
NOME_DO_UID_CONECTIVIDADE = re.compile(r"\bRequestorUid: (\d+) RequestorPkg: ([\w.]+)")
# `INFO.txt` da coleta: `user,com.reau.app,10492` (pacote e uid do app instalado).
UID_NO_INFO = re.compile(r"^\s*\w+,(com\.reau\.app(?:\.lite)?),(\d+)\s*$")
PACOTE_DO_REAU = ("com.reau.app", "com.reau.app.lite")
CONECTIVIDADE = "dumpsys-connectivity"

# Sem o ano: (mês, dia, hora, minuto, segundo, milissegundo) mais o que cada evento diz.
Uniao = namedtuple("Uniao", "quando ms ok nominador uid dbm mhz")
Desconexao = namedtuple("Desconexao", "quando local_gen motivo dbm mhz")


def arquivo(caminhos):
    """O `dumpsys-wifi.txt` (ou, sem ele, o `.mascarado.txt`) dos caminhos: o arquivo solto, a pasta `sistema`, a pasta da coleta (com `sistema/`
    dentro) ou a `diag` dela. Vazio se não há."""
    achados = []
    for c in map(Path, caminhos):
        if c.is_file() and c.name.startswith(NOME):
            achados.append(c)
        elif c.is_dir():
            pastas = [c, c / "sistema"] + ([c.parent, c.parent / "sistema"] if c.name == "diag" else [])
            achados += [p / f"{NOME}{sufixo}.txt" for p in pastas for sufixo in ("", ".mascarado") if (p / f"{NOME}{sufixo}.txt").is_file()]
    nao_mascarados = [a for a in achados if ".mascarado" not in a.name]
    return (nao_mascarados or achados or [None])[0]


def _data(mes, dia, h, m, s, ms, ano):
    try:
        return datetime(ano, int(mes), int(dia), int(h), int(m), int(s)) + timedelta(milliseconds=int(ms))
    except ValueError:
        return None


def ler(caminho, ano):
    """(uniões ao painel, desconexões) do dumpsys, em ordem de hora. Uma união é do painel quando o nome da rede começa com `RE_`; as de outras redes,
    nem o nome nem o endereço, nunca são guardados."""
    if caminho is None:
        return [], []
    unioes, desconexoes = [], []
    for bruta in Path(caminho).read_text(encoding="utf-8", errors="replace").splitlines():
        m = EVENTO_UNIAO.match(bruta)
        if m:
            if not m.group(7).startswith(PAINEL):
                continue
            quando = _data(m.group(1), m.group(2), m.group(3), m.group(4), m.group(5), m.group(6), ano)
            campos = dict(CAMPO.findall(bruta))
            if quando is not None:
                unioes.append(Uniao(quando, _inteiro(campos.get("durationMillis")), campos.get("connectionResult") == "1",
                                    campos.get("connectionNominator", "?"), campos.get("uid", "?"),
                                    _inteiro(campos.get("signalStrength")), _inteiro(campos.get("mChannelInfo"))))
            continue
        m = DESCONEXAO.match(bruta)
        if m:
            quando = _data(m.group(1), m.group(2), m.group(3), m.group(4), m.group(5), m.group(6), ano)
            campos = dict(CAMPO_DESCONEXAO.findall(m.group(7)))
            if quando is not None:
                desconexoes.append(Desconexao(quando, campos.get("local_gen", "?"), campos.get("reason", "?"),
                                              _inteiro(campos.get("lastRssi")), _inteiro(campos.get("lastFreq"))))
    return sorted(unioes), sorted(desconexoes)


def _inteiro(texto):
    try:
        return int(texto)
    except (TypeError, ValueError):
        return None


def mais_perto(desconexoes, quando, antes_s, depois_s):
    """A desconexão mais perto de `quando` (datetime), de `antes_s` segundos antes a `depois_s` depois dele; None se nenhuma."""
    candidatas = [d for d in desconexoes if -antes_s <= (d.quando - quando).total_seconds() <= depois_s]
    return min(candidatas, key=lambda d: abs((d.quando - quando).total_seconds()), default=None)


def fim_da_uniao(uniao, desconexoes):
    """A primeira desconexão depois de a união acabar de se formar (início + duração), ou None (o dumpsys acabou antes)."""
    pronto = uniao.quando + timedelta(milliseconds=uniao.ms or 0)
    return next((d for d in desconexoes if d.quando >= pronto), None)


def nomes_dos_uids(caminho):
    """{uid (texto): pacote} que o dumpsys do Wi-Fi e, na mesma pasta, o do conectividade dizem. Só o nome do pacote, nunca o de uma rede. Vazio sem o arquivo."""
    nomes = {}
    if caminho is None:
        return nomes
    arquivos = [(Path(caminho), NOME_DO_UID_WIFI)]
    pasta = Path(caminho).parent
    for sufixo in ("", ".mascarado"):
        vizinho = pasta / f"{CONECTIVIDADE}{sufixo}.txt"
        if vizinho.is_file():
            arquivos.append((vizinho, NOME_DO_UID_CONECTIVIDADE))
            break
    for arquivo, padrao in arquivos:
        for bruta in arquivo.read_text(encoding="utf-8", errors="replace").splitlines():
            for uid, pacote in padrao.findall(bruta):
                nomes.setdefault(uid, pacote)
    return nomes


def _info_da_coleta(caminhos, caminho_wifi):
    """Os `INFO.txt` onde a coleta pode tê-lo: a pasta do dumpsys e a de cima (a coleta), e as pastas dadas e a de cima de cada uma."""
    pastas = []
    for c in ([Path(caminho_wifi)] if caminho_wifi else []) + [Path(c) for c in caminhos]:
        base = c if c.is_dir() else c.parent
        pastas += [base, base.parent]
    return [p / "INFO.txt" for p in dict.fromkeys(pastas) if (p / "INFO.txt").is_file()]


def uids_do_reau(caminhos, caminho_wifi, nomes):
    """O `uid` do REau na coleta (conjunto de textos, vazio se não há como saber): o `INFO.txt` (`user,com.reau.app,10492`) e o pacote `com.reau.app` nos nomes do dumpsys."""
    uids = {uid for uid, pacote in nomes.items() if pacote in PACOTE_DO_REAU}
    for info in _info_da_coleta(caminhos, caminho_wifi):
        for bruta in info.read_text(encoding="utf-8", errors="replace").splitlines():
            m = UID_NO_INFO.match(bruta)
            if m:
                uids.add(m.group(2))
    return uids
