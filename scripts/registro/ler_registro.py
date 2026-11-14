"""Lê registros do REau puxados do celular e mostra a linha do tempo resumida, acusando os problemas conhecidos.

    python -B scripts\\registro\\ler_registro.py <arquivo ou pasta> [...] [--desde AAAA-MM-DD] [--ate AAAA-MM-DD] [--tudo]

Lê os arquivos `ride-*.log` (uma pasta inteira, ou arquivos soltos), em ordem de nome, e escreve:

- a linha do tempo de cada projeção: ligou, Android Auto conectado, painel no Wi-Fi, transmitindo, primeiro quadro
  confirmado, quedas, vigias, marcas do piloto, travamentos, estado térmico e bateria, desligou, o resumo, e a pior
  leitura do Android Auto presa no decodificador (o `readHeld` das linhas aa-stats);
- os problemas conhecidos: transmitindo sem confirmação do primeiro quadro, vigias, buracos nas linhas de 5 s (ou no
  resumo), quedas seguidas (duas em menos de 60 s), temperatura subindo, o app travou, o Android Auto calado (15 s
  sem mandar nada) e o Android Auto que aceita e não responde (três negociações seguidas sem resposta).

Desde 03/11/2026: a interrupção final da imagem (o painel sumiu e a projeção acabou assim) não é "painel sem imagem"; o laço de
espera só vira problema se o painel apareceu na varredura; a temperatura só alarma com subida de 8 °C sem carregador (a de depois do
carregador sair não conta) ou acima de 44 °C; o Davey da abertura do app (os 5 s depois de `process start`, ou o `slow frame … during
cold start`) não entra. Novas linhas e regras: "tela por tempo" (mapa/menu/apps, dos eventos `aa-screen`); "apps" por mais de 2 min com
`Nav:` chegando e nenhuma tecla do painel; quadros-chave por segundo por ritmo (alarme se a 6 fps passar de 1,2/s); e o painel visto na
varredura com o pedido de rede saindo mais de 10 s depois.

Uma queda de Wi-Fi que estava firme até sumir de uma vez (sinal bom e parado nas últimas amostras) sai na linha do tempo como
"painel desligou (moto desligada?)" e não vira problema, a não ser que o painel volte em até 5 min (aí é o Wi-Fi dele que reiniciou,
mesmo em outro canal). O painel que fica na varredura com sinal bom e recusa todos os pedidos (a linha `dash visible but refusing`
do app, ou, em registro antigo, os `unavailable ... the dash: ... dBm` de 30 s ou mais) sai como problema "painel travado": só desligar
e ligar a ignição resolve. Um `EPERM` ao ligar o socket à rede repetido com o mesmo `network=` é defeito do app (a rede morta reaproveitada,
31/10/2026). "Transmitindo sem confirmação do primeiro quadro" só vale para projeção de 10 s ou mais (o painel avisa aos 10 s). As linhas de uma mesma queda são uma queda só; buraco nas linhas de 5 s só com o painel no ar; o calor
da bateria só conta sem carregador e com a tela ligada; e no fim de cada projeção a linha `vídeo:` (taxa, quadro, quadros-chave,
redução, trocas de perfil).

O Wi-Fi "antes da queda" é o do painel (o último `bike=` das olhadas `[device]`, até 90 s antes); sem ele, o `[link]` do celular, dito como tal.
Um laço de espera pelo painel (pedidos de rede sem um `connected` no meio) sai com a duração e o número de pedidos, e vira problema a
partir de 3 min. Se uma pasta (ou a `diag`, e a de cima) tem `logcat-sistema*.txt`, entram nos problemas o Android Auto que travou
(`FATAL EXCEPTION` do `gearhead`, com o intervalo desde o `byebye` do REau) e o app com `Davey!` ou `Skipped` de 100 quadros ou mais.
Depois de cada queda do Wi-Fi do painel (`link lost — reconnecting`), o que o sistema disse em volta dela nesse logcat (a coleta por adb guarda
os buffers de todos os tipos): os eventos e códigos de motivo do `wpa_supplicant` e as linhas que citam a rede do painel, sem BSSID nem nome.

Desde 03/11/2026: o `logcat-sistema.txt` e o `.mascarado.txt` não contam o mesmo evento duas vezes (fica o não mascarado); o
`sistema/dumpsys-wifi.txt` cruza cada `link lost` com o `NETWORK_DISCONNECTION_EVENT` mais próximo (motivo, `local_gen`) e mostra as uniões ao Wi-Fi do painel
sem nenhum registro do REau (sem BSSID nem nome de rede); a versão do app por abertura e por projeção (da linha `process start`); `idr/janela` e `kbps` médio e
máximo por ritmo (2, 4 e 6 fps); o provisório `apps (a select in the menu)` desmentido pela imagem (só informativo, em "AVISOS DA LEITURA"); "painel visto → pedido → unido" pelo `seen N s ago` da
varredura (alarme com o painel visto há mais de 10 s e o pedido sem sair); e o aviso de que o logcat do sistema não cobre o horário das projeções (seção
"AVISOS DA LEITURA", que não conta como problema). Roda de qualquer pasta e com `python -I` (o caminho dos módulos ao lado vem do próprio arquivo).

Desde 08/11/2026: `--desde AAAA-MM-DD[THH:MM]` e `--ate AAAA-MM-DD[THH:MM]` escolhem a janela, e por padrão uma coleta (`celular-AAAAMMDD-HHMM`) só traz os
registros que acabam depois da coleta anterior (`--tudo` lê todos; um registro inteiro fica ou sai, uma projeção não é cortada); o cabeçalho diz o corte e agrupa por data e build.
Não são mais problema: o `RX watchdog` (é o eco de qualquer queda; o painel que calou com o Wi-Fi ainda de pé vai só nos avisos), o `Auth timed out … not a single packet` (a moto
desligada), o laço de espera cujo painel só aparece na lembrança logo depois da queda ou no fim da espera, e o `dumpsys` sem desconexão (dito uma vez). Entram: o bloco "TESTE DO ITEM
1a" (a busca de redes com o app atrás, `varredura.py`: leituras com redes e vazias na frente e atrás, o tipo `location`, os cortes da pausa e o critério novo do `scan empty`); o tempo sem
imagem de cada queda, no total e com o painel ligado, e o painel ligado → unido pela idade do painel, separando o Wi-Fi que caiu do painel que religou (`quedas.py`); o "visto → pedido →
unido" sem a lembrança do painel que acabou de sair; o `has not joined`, as chamadas, o doze e a economia de bateria; e, nos avisos (não são defeitos), o menu aberto sem tecla com a tela acesa, o
cartão de chamada sobre o mapa e os processos do Android que caíram (`logcat-crash.txt`). (Desde 11/11/2026 lê também as sessões e o vídeo: ver abaixo.)

Desde 08/11/2026: a união ao Wi-Fi do painel que o `dumpsys wifi` traz sem registro do REau só é "do REau" se o `uid` dela é o do
REau (o `INFO.txt` e o pacote `com.reau.app` no dumpsys dizem qual é; sem eles, o `uid` que pediu a rede enquanto o app registrava); as de outro app ou do sistema vão para
"AVISOS DA LEITURA" com o pacote e o `uid` (`wifi_sistema.nomes_dos_uids`), sem nome de rede. `TX send failing`/`RTP send failing` com `NetworkOnMainThreadException` é defeito do app
(o pacote não saiu), não queda. Entendem-se as linhas do ramo do painel: `panel settings:` (a escrita das unidades; acusa o bloco que o soquete recusou, a escrita não confirmada e o
«sent once» sem pacote 04xx/0A02 no `[detail]`), `nav distances in …` e `dash says it changed a setting` (linha do tempo).

Desde 08/11/2026, noite: a união que o app não viu na varredura mas foi a resposta ao pedido do REau diz o número do pedido (só a que o
celular fez sozinho, `joined by the phone itself`, diz que foi ele); o aviso de cobertura do logcat do sistema fala de cada buffer (o `main`, pelas etiquetas do `wpa_supplicant`, do Android
Auto e do `WifiNetworkFactory`, e o `system`, pelas do `ActivityManager`) e a enchente do HAL térmico (`ThermalHalWrapper`) não conta como cobertura e é dita; a queda que é o painel
desligando com a moto sai numa seção "MOTO DESLIGADA", fora de PROBLEMAS e do código de saída; e `--desde`/`--ate` valem também para as uniões do `dumpsys` do Wi-Fi, para o logcat do sistema
e para os `logcat-*.txt` do app (o corte padrão da coleta anterior continua só nos `ride-*.log`).

Desde 10/11/2026: duas ou mais sessões seguidas que chegaram a `→ STREAMING` e caíram (`session fail`) em menos de 60 s
saem como problema "possível laço de reconexão", com a hora e a duração de cada uma. Uma sessão curta isolada não avisa (em 50 sessões reais houve duas, 40,3 s e 30,1 s, nunca seguidas).

Desde 11/11/2026: (1) a seção "CORRIDAS POR CHAVES EXPERIMENTAIS" agrupa as projeções pelo conjunto de chaves que o `settings in use:` e o `connection experiments ON:` dizem ligadas
e compara cada grupo com o «nenhuma» (todas desligadas). CRITÉRIOS, escritos antes dos dados (os números e o porquê estão em `chaves.py`): a medida principal é queda com Wi-Fi firme
(sinal do painel de -75 dBm ou melhor antes da queda; a moto desligada não é queda) por hora transmitindo; as secundárias, só mostradas, são `late` (janelas de 5 s com atraso), kbps
médio em movimento, segundos sem imagem por hora e quedas totais por hora; só há veredito com 1 h e 3 projeções de cada lado; PIOR/MELHOR pede 1,5 vez a taxa da referência e 2 quedas a
mais ou a menos. A legibilidade do mapa não se mede aqui (é o teste cego do vídeo). (2) A seção "LEITURAS DO CLASSIFICADOR DE IMAGEM" lista as `picture look:` com p < 0,98 e a
tela do `aa-screen:` na hora, e o alarme (em PROBLEMAS) dispara com mais de 3 % de leituras sem certeza (7 por minuto sem as linhas `video fps`) e ao menos 10 delas; o limite e o porquê
estão em `olhadas.py`. (3) O batimento e o grupo `03` do painel, por conexão, saem da seção "PACOTES DO PAINEL" (`detalhes.py`, do registro `[detail]`). (4) As sessões (`sessions/`) e o
vídeo (`video/`) de uma coleta saem da seção "GRAVAÇÕES DA COLETA" (`gravacoes.py`: cobertura, partes, rolagem, ocupação do anel, índice do vídeo). (5) O tempo sem imagem conta do
`last dash packet` a partir da linha que o diz, não da primeira linha da queda (98,3 s onde eram ~96 s); a união que a linha `connected` não diz de quem foi não é mais chamada de
«o Android uniu sozinho».

Desde 11/11/2026 (leva 5): as sete chaves experimentais novas (clima velho some, teto de quadros pela bateria, velocidade do GPS no perfil, «inativo» curto como recalculo, cores do
codificador, protocolo 6.0 do Android Auto, vigia tolerante do painel) entram no agrupamento de `chaves.py` pelo que `connection experiments ON:` diz de cada uma, e a linha do tempo diz,
em português, o que elas escrevem (`RX quiet`/`RX back`, `route: a short inactive …`, `speed profile: …`, `encoder colour: …`, `Handshake: asked protocol …`, o teto pela bateria na etiqueta
`heat`). A chave do EPERM não fala mais da trava de Wi-Fi (`bind retries on the same network`); os registros de antes, que diziam a trava, continuam achados por `chaves.py`.

Em registros de versões até a 0.2.0 (o serviço que ligava o servidor sozinho não existe mais), por Ligar, o bloco `servidor do AA:` conta a caminhada que liga o servidor do Android Auto (toque, início
da caminhada, clique em Iniciar, Voltar, fim, primeira conexão, em ms) e acusa a causa: porta fechada 5 s depois do clique, servidor
que só atendeu quando a tela do Android Auto abriu, menu lido pela metade (Android Auto frio?). Sem linhas `aa-server` (o serviço desligado, o caso comum) não diz nada.
"""
import re
import sys
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from pathlib import Path

# With `python -I` (or a script run from another folder) the script's own folder is not on the path: the helpers beside it are found by this file's place.
sys.path.insert(0, str(Path(__file__).resolve().parent))
import chaves  # noqa: E402
import detalhes  # noqa: E402
import gravacoes  # noqa: E402
import logcat_sistema  # noqa: E402
import olhadas  # noqa: E402
import quedas  # noqa: E402
import tempo  # noqa: E402
import varredura  # noqa: E402
import wifi_sistema  # noqa: E402

# A warning or an error has its level at the start of its text ("W: ", "E: "); the reader keeps it apart.
NIVEL = re.compile(r"^([WE]): (.*)$")
LINHA = re.compile(r"^(\d\d:\d\d:\d\d\.\d{3})\s+(?:\+\s*\d+ms\s+)?(?:\[([\w-]+)\]\s+)?(.*)$")
LIGOU = ("---- projeção ligada", "==== session start: projection")
DESLIGOU = ("---- projeção desligada", "==== session end: projection")
TERMICO = ["none", "light", "moderate", "severe", "critical", "emergency", "shutdown"]
# `dark=2/3m02s (max 1m08s)` in the resumo: the interruptions of the picture on the dash (since 20/10/2026), the longest listed above this.
DARK = re.compile(r"dark=(\d+)/(\S+) \(max (\S+)\)")
DARK_PROBLEMA_S = 10
# The dash's Wi-Fi that comes back within this of going is a restart of its access point (or a hung one, cleared by the ignition), not the bike
# switched off for good: 120 s was short (01/11/2026: a hung dash came back after 3 min 54 s, 2422 → 2417 MHz).
VOLTA_S = 300.0
# A projection shorter than this has not got to the dash's own 10 s mark (09 06 55): no first frame is expected.
PROJECAO_CURTA_S = 10.0
# `Binding socket to network 454 failed: EPERM` (the session's sockets refused) and the app's own line when it drops that network (since 02/11/2026).
EPERM_REDE = re.compile(r"Binding socket to network (\d+) failed: EPERM")
EPERM_DESCARTADA = re.compile(r"binding the socket to network (\S+) was refused \(EPERM, #(\d+) in a row")
EPERM_SEGUIDOS_PROBLEMA = 3
# The app's lines after an EPERM (since 08/11/2026): whose the refused network was, and the news that another network took its place (the
# projection is told to try the dash at once, not at the end of its 30 s wait; ride log of 06/11/2026 17:47:21).
EPERM_REDE_DO_CELULAR = "joined by the phone itself"
EPERM_REDE_NO_LUGAR = "took the place of the refused one"
EPERM_ESPERA_S = 15
EPERM_JANELA_S = 10
EPERM_CORTE_JANELA_S = 5
EPERM_ESPERA = re.compile(r"projection: the dash is tried again in (\d+)s")
# The app's line for a dash whose Wi-Fi hung, and (old logs) the unavailable lines that show it.
PAINEL_TRAVADO = re.compile(r"dash visible but refusing for (\d+) s \((\d+) tries")
RECUSA_COM_PAINEL = re.compile(r"unavailable after \d+ms \(mid-session.*?the dash: '[^']*' (-?\d+) dBm \d+ MHz seen (\d+) s ago")
TRAVADO_SINAL_DBM = -75
TRAVADO_VISTO_S = 30  # old logs: the sighting of the line may be this old (the app now takes 15 s)
TRAVADO_MIN_S = 30.0
CANAL_ANTES = re.compile(r"after .+? on (\d+) MHz")
CANAL_DEPOIS = re.compile(r"back on another channel \((\d+) → (\d+) MHz\)|back on the same channel \((\d+) MHz\)")
CANAL_DEVICE = re.compile(r"bike=-?\d+dBm/(\d+)MHz")
# The dash's own Wi-Fi in the phone's [device] look: `bike=-74dBm/2427MHz/58Mbps` (`bike=none` when it is not joined).
BIKE_DEVICE = re.compile(r"bike=(-?\d+)dBm/(\d+)MHz(?:/(\d+)Mbps)?")
BIKE_ATE_S = 90.0  # the look comes every 30 s: older than this is no longer the Wi-Fi "before the fall"
ARQUIVO_DIA = re.compile(r"ride-(\d{4})(\d\d)(\d\d)-")
# A collection folder (`celular-20261107-2327`): its name is the day and the minute it was made, which is how the one before it is known (item 17a, 08/11/2026).
PASTA_DE_COLETA = re.compile(r"^celular-(\d{4})(\d\d)(\d\d)-(\d\d)(\d\d)$")
# The wait for the dash: requests for its Wi-Fi in a row with no `connected` between them.
PEDIDO_DE_REDE = re.compile(r"requestNetwork '.*?' .*?request #(\d+)")
CAMINHADA_FIM = re.compile(r"\b(start|stop|visit) walk ended: \w+ in (\d+) ms")
ESPERA_NA_LINHA_S = 60.0
ESPERA_PROBLEMA_S = 180.0
ESPERA_PAINEL_VISTO_S = 30.0
# A rise of this many degrees without the charger (inside one stretch of use), or a battery above this temperature at any look, is heat
# worth telling. The absolute 38 °C of before fired on 38 → 38 and on the charger's own heat (03/11/2026 sweep).
SOBE_GRAUS = 8.0
QUENTE_GRAUS = 44.0
# The battery stays warm from the charge for this long after the charger leaves: those looks are the charger's heat, not the use's.
CARGA_ESFRIA_S = 600.0
# The screen of Android Auto, by time (`aa-screen: apps (by the picture: …)`, `… confirmed by the picture`) and the rider's keys on the dash.
TELA_AA = re.compile(r"^aa-screen: (nav|menu|apps)\b")
TELA_NOME = {"nav": "mapa", "menu": "menu", "apps": "apps"}
TECLA_DO_PAINEL = re.compile(r"^button \w+ ")
TECLA_AVISO = "the dash's notice, ignored"
TOQUE_NO_CELULAR = "touch down sent to Android Auto"  # the rider's finger on the phone's preview: input as well
NAV_CHEGANDO = re.compile(r"\bNav: (?:position|state)\b")
APPS_SEM_TECLA_S = 120.0
# `video fps target=6 … (31frames/5.1s) … idr=7(max …)`: key frames per second by the pace the window ran at.
VIDEO_RITMO = re.compile(r"target=(\d+)")
VIDEO_JANELA = re.compile(r"\((\d+)frames/([\d.]+)s\)")
QUADROS_CHAVE_6FPS_MAX = 1.2
QUADROS_CHAVE_JANELAS_MIN = 2
# The dash showed in the scan, and the next request for its Wi-Fi went out this long after it was seen.
APP_TARDE_S = 10
PEDIDO_COM_PAINEL = re.compile(r"request #(\d+).*?the dash: '[^']*' -?\d+ dBm \d+ MHz seen (\d+) s ago")
PAINEL_FORA_DA_VARREDURA = "the dash not among them"
# `==== process start: app — app 0.3.0-dev+26.11.03-03.30 (29894790, 7eee9cb), motorola motorola edge 30, Android 14 (API 34) ====`: the build that ran.
# The app's scan lookup that did not find the dash (the wait's own look, every pause), and the app's line when it found it.
PROCURA_SEM_ACHAR = re.compile(r"scan lookup for '[^']*' → not found")
# `aa-screen: apps (a select in the menu)`: the tap on a menu item is taken as an app opened at once, before any picture. The picture then says `menu (by the
# picture: …, from apps)`: it was only a tap on the menu, and the dash was shown the apps' cut for that long.
APPS_PROVISORIO = "aa-screen: apps (a select in the menu)"
MENU_PELA_IMAGEM_DE_APPS = re.compile(r"^aa-screen: menu \(by the picture: .*from apps\)")
APPS_DESMENTIDO_S = 5.0
# The logcat of the system holds less than an hour: a projection with fewer entries than this around it is not covered by it.
COBERTURA_MIN_ENTRADAS = 20
COBERTURA_FOLGA_S = 30.0
COBERTURA_BURACO_S = 1800.0
# The dumpsys wifi's disconnection nearest to the app's `link lost`, this many seconds either side (the system writes it first, by tens of milliseconds).
QUEDA_NO_SISTEMA_S = 10.0
# A dumpsys union to the dash this close to a line of the REau's records (before the first or after the last of a file) has a record.
UNIAO_COM_REGISTRO_S = 30.0
# The app's cold start: its first slow frames (a debug build with no baseline profile) are not the ride's.
ABERTURA_S = 5.0
ABERTURA_FRAME_LENTO = re.compile(r"slow frame: \d+ ms during cold start")
ABERTURA_FRAME_LENTO_S = 1.5
CHAVE_CHEGOU = re.compile(r"key arrived ([\d.]+) s after the first greeting, after (\d+) resends?")
AVISO_DE_10S = ("dash DECODED first IDR", "dash notice 09 06 55", "dash sent 09 06 55 while")
# Android Auto closes its link together with the phone's screen going off or on (EOF, 12:01:07 and 12:13:36 on 20/10/2026): not a fall.
TELA_JUNTO_S = 3.0
TELA_EVENTOS = ("screen on", "screen off", "screen unlocked")
SPS = "H.264 SPS parsed"
BURACO_5S = 6.0  # a linha de 5 s chegando mais de 1 s atrasada
QUEDAS_SEGUIDAS_S = 60.0
SESSAO_CURTA_S = 60.0  # a session that reached STREAMING and fell before this
SESSOES_CURTAS_LACO = 2  # this many in a row look like a reconnect loop (the dash coming up and falling again and again)
QUEDA_JUNTA_S = 10.0  # the signs of one fall (Wi-Fi lost, dash silent, session ended) come within this of each other
DESLIGAMENTO_S = 3.0
NEGOCIACOES_SEM_RESPOSTA = 3
NEGOCIACAO_OK = "Handshake successful"
NEGOCIACAO_FALHOU = "AaReceiver: the handshake failed"
CALADO = "WiFi read timeout (15s)"
BYE = "AapTransport stopping and sending byebye"
BYE_S = 2.0
# The detailed log's lines (~98% of a file with the switch on); nothing here reads them, so they are skipped.
DETALHE = "  [detail] "
# A send the OS refused because of the thread it was called from (the policy, not the network): nothing went to the wire, and the line's "link down?" is wrong.
ENVIO_POR_POLITICA = re.compile(r"\b(TX|RTP) send (?:failing|refused)\b.*\b(NetworkOnMainThreadException)\b")
# The settings written to the dash (the branch's screen "Painel"): `panel settings: writing 04/01=AA … (changes: temperature CELSIUS -> FAHRENHEIT)` and its answers.
PAINEL_ESCRITA = "panel settings: "
PAINEL_MUDANCAS = re.compile(r"\(changes: ([^)]*)\)")
PAINEL_RESULTADO = re.compile(r"^panel settings: (CONFIRMED|NOT confirmed) after ([\d.]+) s: (.*)$")
PAINEL_ENVIADO = "panel settings: sent once;"
PAINEL_RECUSADO = "panel settings: the socket refused the block"
PAINEL_TX = re.compile(r"\bdash tx\b.*(?<![0-9A-Fa-f:])(?:04[0-9A-Fa-f]{2}|0A02)(?=[: ]|$)")
PAINEL_TX_JANELA_S = 3.0
PAINEL_NAV_UNIDADE = re.compile(r"^nav distances in (.*?) \((.*)\)$")
PAINEL_AVISO_09_05 = re.compile(r"^dash says (?:it changed a setting \(09 05 = 01\)|09 05 = (\S+), not the 01)")
# Sinais de que a transmissão acabou ou recomeçou entre duas linhas de 5 s: o intervalo não é buraco.
SEM_VIDEO = ("session fail", "video loop stop", "→ STREAMING")

# (o que procurar no texto, o que dizer na linha do tempo)
EVENTOS = [
    # Conectado só com a negociação respondida; discar e o servidor aceitar não basta.
    (NEGOCIACAO_OK, "Android Auto conectado"),
    (NEGOCIACAO_FALHOU, "Android Auto não respondeu à negociação"),
    (CALADO, "Android Auto calado (15 s sem mandar nada)"),
    ("AaReceiver: session ended (clean=false", "Android Auto caiu"),
    ("→ STREAMING", "transmitindo"),
    ("dash DECODED first IDR", "primeiro quadro confirmado pelo painel"),
    # Since 20/10/2026 the log says what the notice is: the dash's own 10 s mark, not proof of a decoded frame.
    ("dash notice 09 06 55", "aviso de 10 s do painel (09 06 55)"),
    ("link lost — reconnecting", "Wi-Fi da moto caiu"),
    ("dash visible but refusing", "painel visível mas recusando (Wi-Fi do painel provavelmente travado; piloto avisado)"),
    ("send tail before", "o que o app enviava antes do silêncio"),
    ("bike network lost", "Wi-Fi da moto perdido"),
    # O Wi-Fi no instante de um vigia do painel (sinal, velocidades, banda) e o último minuto, para dizer
    # Wi-Fi fraco de painel calado. A linha é longa de propósito: o minuto de sinal é o que interessa.
    ("link at failure:", "Wi-Fi na falha"),
    # The entry: how long the dash had been on at its first heartbeat, and what its key cost.
    ("dash heartbeat age", "auth: idade do painel"),
    ("key arrived", "auth: chave"),
    ("watchdog:", "vigia"),
    ("Decoder restart requested", "vigia do decodificador"),
    ("the app crashed", "o app travou"),
]
# "RX watchdog:" with the colon: the dash's "no 09 06 55 after 15s" notice only mentions the watchdog in passing.
QUEDAS = ("link lost — reconnecting", "RX watchdog:", "bike network lost", "AaReceiver: session ended (clean=false")
# A fall whose Wi-Fi was steady until it went (the dash's own Wi-Fi vanished at once) is the bike being switched off.
HISTORICO_RSSI = re.compile(r"last 60 s, every 5 s: rssi ([^|]*)")
RSSI_FRACO_DBM = -80
RSSI_VARIACAO_DB = 12
AMOSTRAS_RSSI = 4
# The server's start walk (só com o serviço de acessibilidade ligado pelo usuário): the lines of the tags aa-server and AaReceiver that tell it.
SERVIDOR_TOQUE = "the cover up at the rider's Ligar"
SERVIDOR_INICIO = "start walk begins"
SERVIDOR_CLIQUE = "clicked start"
SERVIDOR_VOLTAR = re.compile(r"\+\d+ ms back$")
SERVIDOR_FIM = "start walk ended: "
SERVIDOR_PORTA_FECHADA = "port still closed after 5 s"
SERVIDOR_ABRIU = ("rider opened Android Auto settings", "opened the settings")
SERVIDOR_CONECTOU = "AaReceiver: connected at last after"
# The visit: the settings in front, nothing touched; the next walk, if the port stayed closed, is the one more start.
SERVIDOR_VISITA_OK = "the visit brought the server up"
SERVIDOR_VISITA_NAO = "the visit did not bring the port up"
SERVIDOR_MENU = re.compile(r"\(menu: ([^)]*)\)")
SERVIDOR_ATENDEU_APOS_S = 2.0  # connected this soon after Android Auto's screen opened, with no click between: it answered because of the screen
SERVIDOR_TOQUE_PERTO_S = 5.0  # the projection mark counts as the tap when the walk begins this soon after it
SERVIDOR_MENU_PELA_METADE = 2  # a menu of at most this many items is Android Auto still being built


@dataclass
class Linha:
    arquivo: str
    hora: str
    etiqueta: str
    texto: str
    nivel: str = ""  # "W" or "E" when the line says so at the start of its text, else empty
    dia: str = ""  # the day (YYYY-MM-DD) from the file's name, one more when the clock passes midnight inside it; empty without a dated name

    @property
    def segundos(self):
        h, m, s = self.hora.split(":")
        return int(h) * 3600 + int(m) * 60 + float(s)


def _registros_de(caminhos):
    """Os `ride-*.log` dos caminhos: pasta com eles, pasta de coleta (os de `diag/`, e só se ela mesma não os tem) ou arquivo solto. Devolve (arquivos, caminhos sem nenhum)."""
    arquivos, sem = [], []
    for c in map(Path, caminhos):
        if c.is_dir():
            achados = sorted(c.glob("ride-*.log")) or sorted((c / "diag").glob("ride-*.log"))
            arquivos += achados
            if not achados:
                sem.append(c)
        elif not c.name.startswith("logcat"):
            arquivos.append(c)  # the system logcat is read apart (logcat_sistema)
    return list(dict.fromkeys(arquivos)), sem


def _coleta_de(caminho):
    """A pasta de coleta (`celular-AAAAMMDD-HHMM`) de um caminho: ele mesmo, ou a pasta de cima se ele é a `diag` dela; None se não é de uma coleta."""
    c = Path(caminho).resolve()
    for candidata in (c, c.parent) if c.name == "diag" else (c,):
        if PASTA_DE_COLETA.match(candidata.name):
            return candidata
    return None


def _quando_da_coleta(pasta):
    m = PASTA_DE_COLETA.match(pasta.name)
    return datetime(*(int(g) for g in m.groups())) if m else None


def coleta_anterior(caminho):
    """(pasta, quando) da coleta mais recente que veio antes da de `caminho` (as pastas `celular-AAAAMMDD-HHMM` ao lado dela), ou None se o caminho não é de uma coleta ou
    não há outra antes. É o corte padrão da leitura: o que a coleta anterior já trouxe (e já foi analisado) não entra de novo."""
    pasta = _coleta_de(caminho)
    if pasta is None:
        return None
    esta = _quando_da_coleta(pasta)
    antes = [(p, _quando_da_coleta(p)) for p in pasta.parent.iterdir() if p.is_dir() and PASTA_DE_COLETA.match(p.name)]
    antes = [(p, q) for p, q in antes if q < esta]
    return max(antes, key=lambda pq: pq[1]) if antes else None


def _limites(arquivo_linhas):
    """(primeiro, último) instante das linhas de um registro (None, None se o nome do arquivo não traz o dia)."""
    quandos = [q for q in (instante(arquivo_linhas[0]), instante(arquivo_linhas[-1])) if q is not None] if arquivo_linhas else []
    return (min(quandos), max(quandos)) if quandos else (None, None)


def ler(caminhos, desde=None, ate=None, corte_padrao=False, info=None):
    """As linhas de todos os `ride-*.log` dos caminhos (pastas, a raiz de uma coleta com `diag/`, ou arquivos), em ordem de nome.

    `desde` e `ate` (datetime) limitam aos registros que têm algum instante na janela (o registro inteiro fica ou sai: uma projeção não é cortada ao meio); um registro cujo
    nome não traz o dia nunca sai. Com `corte_padrao` (e sem `desde`), cada caminho que é uma coleta só traz os registros que acabam depois da coleta anterior (`coleta_anterior`),
    se houver. `info`, se dado, recebe `excluidos` (nomes dos registros deixados de fora) e `cortes` ([(pasta da coleta anterior, quando)])."""
    info = info if info is not None else {}
    info.setdefault("excluidos", [])
    info.setdefault("cortes", [])
    origem = {}
    for c in caminhos:
        for a in _registros_de([c])[0]:
            origem.setdefault(a, c)
    corte_de = {}
    if corte_padrao and desde is None:
        for c in dict.fromkeys(map(str, caminhos)):
            anterior = coleta_anterior(c)
            if anterior:
                corte_de[c] = anterior[1]
                info["cortes"].append(anterior)
    arquivos, _ = _registros_de(caminhos)
    linhas = []
    for a in arquivos:
        dia, anterior = _dia_do_arquivo(a), -1.0
        do_arquivo = []
        for bruta in a.read_text(encoding="utf-8", errors="replace").splitlines():
            if DETALHE in bruta:
                continue
            m = LINHA.match(bruta)
            if m:
                nivel = NIVEL.match(m.group(3))
                texto, letra = (nivel.group(2), nivel.group(1)) if nivel else (m.group(3), "")
                x = Linha(a.name, m.group(1), m.group(2) or "", texto, letra, dia.isoformat() if dia else "")
                if dia and x.segundos < anterior - 3600:
                    dia += timedelta(days=1)  # the clock went back by hours: it is the next day
                    x.dia = dia.isoformat()
                anterior = x.segundos
                do_arquivo.append(x)
        primeiro, ultimo = _limites(do_arquivo)
        minimo = desde if desde is not None else corte_de.get(str(origem.get(a)))
        if primeiro is not None and ((minimo is not None and ultimo < minimo) or (ate is not None and primeiro > ate)):
            info["excluidos"].append(a.name)
            continue
        linhas += do_arquivo
    return linhas


def _dia_do_arquivo(arquivo):
    m = ARQUIVO_DIA.match(arquivo.name)
    try:
        return date(int(m.group(1)), int(m.group(2)), int(m.group(3))) if m else None
    except ValueError:
        return None


instante = tempo.instante  # o instante (datetime) de uma linha, ou None se o nome do arquivo não traz o dia


MOTO_DESLIGADA = "(moto desligada)"  # na linha de uma queda que é o painel desligando com a moto: o `main` a tira dos PROBLEMAS e a põe numa seção própria


def _na_janela(quando, janela):
    """`quando` (datetime) cai na janela (desde, ate) de `--desde`/`--ate`; sem janela, ou sem instante, sim (o que não tem hora não sai)."""
    if janela is None or quando is None:
        return True
    desde, ate = janela
    return (desde is None or quando >= desde) and (ate is None or quando <= ate)


def _da_moto(linhas):
    """Os ids das linhas de queda que são o painel desligando com a moto (o Wi-Fi firme que sumiu de uma vez e não voltou em 5 min: `_painel_desligou`), de todas as projeções."""
    return {id(x) for bloco in projecoes(linhas) for g in _grupos_de_queda(bloco) if _painel_desligou(g, bloco) for x in g}


def do_sistema(caminhos, linhas, janela=None):
    """Os achados do logcat do sistema (`logcat-sistema*.txt` nos caminhos): o Android Auto que travou e o app que perdeu quadros.
    O logcat não traz o ano; vem do dia dos registros. `janela` (desde, ate) de `--desde`/`--ate` vale para o logcat do sistema e para os `logcat-*.txt` do app."""
    entradas = logcat_sistema.ler(caminhos)
    do_app = logcat_sistema.ler(caminhos, so_do_app=True)
    if not entradas and not do_app:
        return []
    byebyes = [t for x in linhas if BYE in x.texto for t in [instante(x)] if t]
    dias = [x.dia for x in linhas if x.dia]
    ano = int(dias[0][:4]) if dias else datetime.now().year
    # Only the days of the registros read: the logcat files of a whole folder cover many days, and the report is about these.
    if dias:
        dos_dias = {(int(d[5:7]), int(d[8:10])) for d in dias}
        entradas = [e for e in entradas if (e.mes, e.dia) in dos_dias]
        do_app = [e for e in do_app if (e.mes, e.dia) in dos_dias]
    if janela is not None:
        entradas = [e for e in entradas if _na_janela(logcat_sistema._instante(e, ano, []), janela)]
        do_app = [e for e in do_app if _na_janela(logcat_sistema._instante(e, ano, []), janela)]
    # The app's first slow frames, opening (cold start, a debug build with no baseline profile), are not the ride's: they are left out.
    janelas = _aberturas(linhas)
    entradas = [e for e in entradas if not _da_abertura(e, ano, janelas)]
    do_app = [e for e in do_app if not _da_abertura(e, ano, janelas)]
    saida = logcat_sistema.travadas_do_android_auto(entradas, byebyes, ano) if entradas else []
    # What the system said of the dash's Wi-Fi around each of its drops (wpa_supplicant's reasons, ConnectivityService), when the collection has it.
    quedas = [t for x in linhas if "link lost — reconnecting" in x.texto for t in [instante(x)] if t]
    da_moto = _da_moto(linhas)
    quedas_da_moto = {t for x in linhas if id(x) in da_moto and "link lost — reconnecting" in x.texto for t in [instante(x)] if t}
    saida += logcat_sistema.wifi_nas_quedas(entradas, quedas, ano, quedas_da_moto) if entradas and quedas else []
    if do_app:
        # The app's own logcat has the stalls one by one, with the walk they fell in; the system's would only repeat them as a total.
        por_evento = logcat_sistema.travadas_do_app_por_evento(do_app, ano, _caminhadas(linhas))
        # An app logcat with no stall in it (small, or rolled): the system's total, if there is one, still says what it saw.
        return saida + (por_evento or logcat_sistema.travadas_do_app(entradas))
    return saida + logcat_sistema.travadas_do_app(entradas)


MOTIVO_DO_SISTEMA = {"3:DEAUTH_LEAVING": "o ponto de acesso avisou que estava saindo", "-1:UNSPECIFIED": "sem motivo dado"}
# Quem pediu a rede, quando é o REau (o `uid` da união é o dele) ou a sugestão de rede que o sistema usa sozinho com o REau fechado.
NOMINADOR_DO_REAU = {"NOMINATOR_SUGGESTION": "sugestão de rede do sistema", "NOMINATOR_SPECIFIER": "pedido do REau"}
# O que o sistema diz de quem uniu, para o que não é do REau.
NOMINADOR_DE_OUTRO = {"NOMINATOR_SAVED": "rede salva", "NOMINATOR_UNKNOWN": "origem desconhecida", "NOMINATOR_SPECIFIER": "pedido de um app",
                      "NOMINATOR_SUGGESTION": "sugestão de rede"}
PRIMEIRO_UID_DE_APP = 10000  # o `uid` abaixo deste é do próprio Android


def _desconexao_texto(d):
    """`local_gen=false (quem derrubou foi o outro lado, o painel), reason=3:DEAUTH_LEAVING (…), último sinal -59 dBm, 2422 MHz`."""
    quem = {"true": "o celular derrubou", "false": "quem derrubou foi o outro lado, o painel"}.get(d.local_gen)
    motivo = MOTIVO_DO_SISTEMA.get(d.motivo)
    partes = [f"local_gen={d.local_gen}" + (f" ({quem})" if quem else ""), f"reason={d.motivo}" + (f" ({motivo})" if motivo else "")]
    if d.dbm is not None:
        partes.append(f"último sinal {d.dbm} dBm")
    if d.mhz:
        partes.append(f"{d.mhz} MHz")
    return ", ".join(partes)


def _origem_do_uid(uid, nomes):
    """`com.exemplo.outroapp (uid 10223)`, `o sistema do Android (uid 1010)` ou `um app sem nome nesta coleta (uid 10999)`: só o pacote, nunca uma rede."""
    if uid in nomes:
        return f"{nomes[uid]} (uid {uid})"
    if uid.isdigit() and int(uid) < PRIMEIRO_UID_DE_APP:
        return f"o sistema do Android (uid {uid})"
    return f"um app sem nome nesta coleta (uid {uid})"


def _unioes_sem_registro(caminhos, linhas, janela=None):
    """As uniões ao Wi-Fi do painel que o dumpsys do Wi-Fi traz fora de qualquer registro do REau (dos dias lidos), separadas por de quem são: `do_reau` [(união, quem)] são
    as do `uid` do REau (o `INFO.txt` e o pacote `com.reau.app` do dumpsys dizem qual é; sem eles, o `uid` que pediu a rede enquanto o app registrava) e a sugestão de rede do
    sistema; `de_outros` [(união, origem)] são as de outro app ou do sistema (`origem` com o pacote, quando o dumpsys ou o conectividade o dizem). Um pedido de app cujo `uid` não
    dá para comparar (a coleta não diz o do REau) fica em `do_reau`, dito assim. {"do_reau", "de_outros", "desconexoes"}."""
    achado = {"do_reau": [], "de_outros": [], "desconexoes": []}
    dias = sorted({x.dia for x in linhas if x.dia})
    caminho = wifi_sistema.arquivo(caminhos)
    if caminho is None or not dias:
        return achado
    unioes, achado["desconexoes"] = wifi_sistema.ler(caminho, int(dias[0][:4]))
    nomes = wifi_sistema.nomes_dos_uids(caminho)
    reau = wifi_sistema.uids_do_reau(caminhos, caminho, nomes)
    spans = _intervalos_dos_arquivos(linhas)
    if not reau:
        # Without the INFO.txt or a package name: whoever asked for the network while the app was logging is the REau.
        reau = {u.uid for u in unioes if u.nominador == "NOMINATOR_SPECIFIER" and u.uid != "?" and any(a <= u.quando <= b for a, b in spans)}
    for u in unioes:
        if u.quando.date().isoformat() not in dias or any(a <= u.quando <= b for a, b in spans) or not _na_janela(u.quando, janela):
            continue
        if u.uid in reau:
            achado["do_reau"].append((u, f"{NOMINADOR_DO_REAU.get(u.nominador, u.nominador)}, uid {u.uid}"))
        elif u.nominador == "NOMINATOR_SUGGESTION":
            achado["do_reau"].append((u, NOMINADOR_DO_REAU["NOMINATOR_SUGGESTION"]))
        elif u.nominador == "NOMINATOR_SPECIFIER" and not reau:
            achado["do_reau"].append((u, f"pedido de um app, uid {u.uid}; a coleta não diz o uid do REau, então não deu para saber se é ele"))
        else:
            achado["de_outros"].append((u, _origem_do_uid(u.uid, nomes), NOMINADOR_DE_OUTRO.get(u.nominador, u.nominador)))
    return achado


def avisos_do_dumpsys_wifi(caminhos, linhas, janela=None):
    """As uniões ao Wi-Fi do painel que NÃO são do REau (outro app ou o sistema), uma linha por origem (pacote e `uid`): quantas, quando e a mais longa. Não são defeito do app:
    o REau não pediu essas redes (o `uid` da união é de outro). Sem nome de rede."""
    achado = _unioes_sem_registro(caminhos, linhas, janela)
    grupos = {}
    for u, origem, como in achado["de_outros"]:
        grupos.setdefault((origem, como), []).append(u)
    saida = []
    for (origem, como), unioes in grupos.items():
        certas = [u for u in unioes if u.ok]
        paradas = [(fim.quando - u.quando).total_seconds() for u in certas for fim in [wifi_sistema.fim_da_uniao(u, achado["desconexoes"])] if fim]
        quando = f"{unioes[0].quando:%d/%m %H:%M:%S}" + (f" a {unioes[-1].quando:%H:%M:%S}" if unioes[-1].quando != unioes[0].quando else "")
        falhas = len(unioes) - len(certas)
        partes = [f"{len(certas)} {'união' if len(certas) == 1 else 'uniões'}"] + ([f"{falhas} {'tentativa' if falhas == 1 else 'tentativas'} sem sucesso"] if falhas else [])
        mais_longa = f"; a mais longa ficou {tempo.duracao_texto(max(paradas))}" if paradas else ""
        saida.append(f"{quando}: o celular entrou no Wi-Fi do painel por outro app ou pelo sistema, não pelo REau — {origem}, {como}: {', '.join(partes)}{mais_longa}")
    return saida


def do_dumpsys_wifi(caminhos, linhas, janela=None):
    """O que o `sistema/dumpsys-wifi.txt` diz (ver `wifi_sistema`): para cada `link lost` do app, o `NETWORK_DISCONNECTION_EVENT` mais perto (a até 10 s) com o motivo
    e quem derrubou; e as uniões do celular ao Wi-Fi do painel que não têm registro do REau e SÃO do REau (o `uid` dele; o app fechado: a sugestão de rede do sistema, `NOMINATOR_SUGGESTION`,
    03/11/2026 18:42 a 18:46). Sem BSSID nem nome de rede. Só os dias dos registros lidos. Vazio sem o arquivo ou sem registro com dia (o ano vem do nome do `ride-…`)."""
    dias = sorted({x.dia for x in linhas if x.dia})
    caminho = wifi_sistema.arquivo(caminhos)
    if caminho is None or not dias:
        return []
    _, desconexoes = wifi_sistema.ler(caminho, int(dias[0][:4]))
    saida, sem_evento = [], []
    da_moto = _da_moto(linhas)
    for x in linhas:
        quando = instante(x) if "link lost — reconnecting" in x.texto else None
        if quando is None:
            continue
        d = wifi_sistema.mais_perto(desconexoes, quando, QUEDA_NO_SISTEMA_S, QUEDA_NO_SISTEMA_S)
        if d is None:
            sem_evento.append(x)  # said once, after the others (17 drops of the 07/11 collection came out as 17 lines of the same words)
            continue
        dt = round((d.quando - quando).total_seconds() * 1000)
        quando_texto = f"{abs(dt)} ms {'antes' if dt <= 0 else 'depois'} da linha do app"
        moto = f" {MOTO_DESLIGADA}" if id(x) in da_moto else ""
        saida.append(f"{x.hora[:8]}: o Wi-Fi do painel caiu{moto} — o dumpsys do Wi-Fi registrou NETWORK_DISCONNECTION_EVENT às {d.quando:%H:%M:%S.%f}"[:-3]
                     + f" ({quando_texto}): {_desconexao_texto(d)}")
    if len(sem_evento) == 1:
        saida.append(f"{sem_evento[0].hora[:8]}: o Wi-Fi do painel caiu — o dumpsys do Wi-Fi não traz desconexão a {QUEDA_NO_SISTEMA_S:g} s dessa queda "
                     "(ele guarda só os últimos eventos)")
    elif sem_evento:
        horas = ", ".join(x.hora[:8] for x in sem_evento[:6]) + (f" e mais {len(sem_evento) - 6}" if len(sem_evento) > 6 else "")
        saida.append(f"{sem_evento[0].hora[:8]}: {len(sem_evento)} quedas do Wi-Fi do painel sem desconexão a {QUEDA_NO_SISTEMA_S:g} s no dumpsys do Wi-Fi "
                     f"(às {horas}): ele guarda só os últimos eventos, então o motivo dessas não está aqui")
    for u, quem in _unioes_sem_registro(caminhos, linhas, janela)["do_reau"]:
        if u.ok:
            fim = wifi_sistema.fim_da_uniao(u, desconexoes)
            ficou = (f"saiu às {fim.quando:%H:%M:%S} depois de {tempo.duracao_texto((fim.quando - u.quando).total_seconds())} ({_desconexao_texto(fim)})" if fim
                     else "o dumpsys não mostra quando saiu")
            saida.append(f"{u.quando:%d/%m %H:%M:%S}: o celular entrou no Wi-Fi do painel sem nenhum registro do REau ({quem}, {u.ms} ms para unir, "
                         f"{u.dbm} dBm, {u.mhz} MHz); {ficou}")
        else:
            saida.append(f"{u.quando:%d/%m %H:%M:%S}: o celular tentou entrar no Wi-Fi do painel por {tempo.duracao_texto((u.ms or 0) / 1000)} sem nenhum registro do REau "
                         f"({quem}) e não conseguiu")
    return saida


def _intervalos_dos_arquivos(linhas):
    """[(início, fim)] de cada registro (`ride-…`): da primeira à última linha com hora, com `UNIAO_COM_REGISTRO_S` s de folga para cada lado."""
    por = {}
    for x in linhas:
        quando = instante(x)
        if quando is not None:
            a, b = por.get(x.arquivo, (quando, quando))
            por[x.arquivo] = (min(a, quando), max(b, quando))
    folga = timedelta(seconds=UNIAO_COM_REGISTRO_S)
    return [(a - folga, b + folga) for a, b in por.values()]


def avisos_da_leitura(caminhos, linhas, janela=None):
    """O que a leitura não pôde ver, e não é defeito do app: o logcat do sistema (o buffer guarda menos de 1 h; 37 min na coleta de 03/11/2026) que não cobre o horário
    das projeções, e registros de mais de uma versão do app. Vazio quando está tudo coberto."""
    saida = []
    _, sem = _registros_de(caminhos)
    for c in sem:
        saida.append(f"{c}: nenhum `ride-*.log` nesta pasta nem em `diag/`: só o que o sistema guarda (logcat, `sistema/`) foi lido")
    # Informative only (the owner decided not to fix it, item 15): the provisional "apps" that the picture then contradicts, with its count.
    for bloco in projecoes(linhas):
        saida += _apps_provisorio(bloco)
        saida += _informes(bloco)
    saida += [achado for achado, do_app in _crash_da_coleta(caminhos, linhas) if not do_app]
    # What this reader does not read: the recordings of the sessions and the video (done with scripts of the draft folder so far).
    if any((Path(c) / pasta).is_dir() or (Path(c).parent / pasta).is_dir() for c in caminhos for pasta in ("sessions", "video")):
        saida.append("lacuna conhecida: este leitor não lê as gravações das sessões do Android Auto (`sessions/`) nem o vídeo do painel (`video/`); a análise delas segue por "
                     "scripts à parte")
    entradas = logcat_sistema.ler(caminhos)
    dias = [x.dia for x in linhas if x.dia]
    if entradas and dias:
        ano = int(dias[0][:4])
        # The thermal HAL flood (08/11/2026: 71 % of the file) fills every hour of the file: it is not coverage.
        instantes = sorted(t for e in entradas if not logcat_sistema.e_enchente(e) for t in [logcat_sistema._instante(e, ano, [])] if t)
        trechos = [(instantes[0], instantes[0], 1)] if instantes else []
        for t in instantes[1:]:
            if (t - trechos[-1][1]).total_seconds() > COBERTURA_BURACO_S:
                trechos.append((t, t, 1))
            else:
                trechos[-1] = (trechos[-1][0], t, trechos[-1][2] + 1)
        if trechos:
            do_logcat = max(trechos, key=lambda c: c[2])
            projecoes_lidas = [(instante(b[0]), instante(b[-1])) for b in projecoes(linhas)]
            projecoes_lidas = [(a, z) for a, z in projecoes_lidas if a and z]
            folga = timedelta(seconds=COBERTURA_FOLGA_S)
            sem = [(a, z) for a, z in projecoes_lidas if sum(1 for t in instantes if a - folga <= t <= z + folga) < COBERTURA_MIN_ENTRADAS]
            if sem:
                saida.append(f"o logcat do sistema cobre só {do_logcat[0]:%d/%m %H:%M} a {do_logcat[1]:%H:%M} ({do_logcat[2]} linhas) e não cobre {len(sem)} das "
                             f"{len(projecoes_lidas)} projeções lidas (a primeira, {sem[0][0]:%d/%m %H:%M}): o buffer do Android guarda menos de 1 h, então o que o sistema "
                             "disse em volta das quedas e das travadas dessas projeções não está aqui (colete logo depois da corrida)")
        saida += _cobertura_por_buffer(entradas, ano, [(instante(b[0]), instante(b[-1])) for b in projecoes(linhas)])
    saida += avisos_do_dumpsys_wifi(caminhos, linhas, janela)
    versoes = {(v, d) for _, _, v, d in _versoes(linhas)}
    if len(versoes) > 1:
        saida.append(f"os registros são de {len(versoes)} versões do app: " + "; ".join(versoes_do_app(linhas)))
    return saida


def _cobertura_por_buffer(entradas, ano, projecoes_):
    """O logcat do sistema é de dois buffers de 16 MiB, e o `main` (o `wpa_supplicant`, o Android Auto, o `WifiNetworkFactory`) pode começar bem depois do `system`: a enchente do HAL
    térmico o esvazia (08/11/2026: `main` só de 18:24:53, `system` de 08:39, e a contagem de linhas por horário achava tudo coberto). Um aviso por buffer que só começa depois do
    começo de uma projeção lida (inteira fora, ou em parte), pelas etiquetas de cada um; nada se não houver linha das etiquetas (não dá para saber) nem da enchente por contar."""
    saida = []
    folga = timedelta(seconds=COBERTURA_FOLGA_S)
    projecoes_ = [(a, z) for a, z in projecoes_ if a and z]
    por_buffer = {}
    for nome in logcat_sistema.ETIQUETAS_DO_BUFFER:
        achadas = [(t, e) for e in entradas if logcat_sistema.etiqueta_do_buffer(e, nome) for t in [logcat_sistema._instante(e, ano, [])] if t]
        if achadas:
            por_buffer[nome] = min(achadas, key=lambda c: c[0])
    for nome, (comeco, primeira) in por_buffer.items():
        fora = [(a, z) for a, z in projecoes_ if z + folga < comeco]
        em_parte = [(a, z) for a, z in projecoes_ if a - folga < comeco <= z + folga]
        if not fora and not em_parte:
            continue
        outros = [f"o `{o}` vai desde {c:%d/%m %H:%M}" for o, (c, _) in por_buffer.items() if o != nome and c < comeco]
        primeira_projecao = min(fora + em_parte)[0]
        saida.append(f"o buffer `{nome}` do logcat do sistema só começa às {comeco:%d/%m %H:%M:%S} (a primeira linha dele, de `{primeira.tag}`)"
                     + (f", enquanto {'; '.join(outros)}" if outros else "")
                     + f": {len(fora)} das {len(projecoes_)} projeções lidas ficam inteiras sem as etiquetas dele e {len(em_parte)} em parte (a primeira, {primeira_projecao:%d/%m %H:%M}); "
                     + ("o que o sistema disse de Wi-Fi (`wpa_supplicant`, `WifiNetworkFactory`) e do Android Auto (`GH.`, `CAR.`) nelas não está aqui"
                        if nome == "main" else "o que o `system_server` disse (`ActivityManager`, `AppOps`) nelas não está aqui"))
    enchente = [e for e in entradas if logcat_sistema.e_enchente(e)]
    if enchente and len(enchente) * 10 >= len(entradas):
        saida.append(f"o logcat do sistema tem {len(enchente)} linhas ({100 * len(enchente) // len(entradas)} %) do HAL térmico (`ThermalHalWrapper`, `android.hardware.thermal`), "
                     "que o celular escreve sem parar com o HAL morto; elas enchem o buffer e expulsam o resto, e não entram na conta da cobertura")
    return saida


def _aberturas(linhas):
    """As janelas (início, fim) em que o app está abrindo: os `ABERTURA_S` s depois de cada `==== process start:` e o redor de cada
    `slow frame: N ms during cold start` do próprio registro (o app diz que o quadro lento é da abertura)."""
    saida = []
    for x in linhas:
        quando = instante(x)
        if quando is None:
            continue
        if x.texto.startswith(tempo.PROCESSO_COMECOU):
            saida.append((quando, quando + timedelta(seconds=ABERTURA_S)))
        elif ABERTURA_FRAME_LENTO.search(x.texto):
            folga = timedelta(seconds=ABERTURA_FRAME_LENTO_S)
            saida.append((quando - folga, quando + folga))
    return saida


def _da_abertura(e, ano, janelas):
    """A entrada do logcat é um `Davey!` ou `Skipped N frames` que cai numa janela de abertura do app."""
    if not (logcat_sistema.DAVEY.search(e.texto) or (e.tag == "Choreographer" and logcat_sistema.PULADOS.search(e.texto))):
        return False
    quando = logcat_sistema._instante(e, ano, [])
    return quando is not None and any(inicio <= quando <= fim for inicio, fim in janelas)


def _caminhadas(linhas):
    """As caminhadas do servidor do Android Auto do registro: (modo, início, fim), do `<modo> walk ended: … in N ms`."""
    saida = []
    for x in linhas:
        m = CAMINHADA_FIM.search(x.texto)
        fim = instante(x) if m else None
        if fim:
            saida.append((m.group(1), fim - timedelta(milliseconds=int(m.group(2))), fim))
    return saida


def projecoes(linhas):
    """As linhas de cada projeção, da marca de ligar à de desligar (ou ao fim do arquivo)."""
    blocos, atual = [], None
    for x in linhas:
        if x.texto.startswith(LIGOU):
            atual = [x]
            blocos.append(atual)
        elif atual is not None:
            atual.append(x)
            if x.texto.startswith(DESLIGOU):
                atual = None
    return blocos


def _do_desligamento(x, bloco):
    """O fim da sessão do Android Auto que o próprio desligamento provoca (menos de 3 s antes da marca): não é queda."""
    fim = bloco[-1]
    return ("AaReceiver: session ended" in x.texto and fim.texto.startswith(DESLIGOU)
            and fim.segundos - x.segundos < DESLIGAMENTO_S)


def _causa_da_queda(x):
    """O nome curto do que uma linha de queda diz."""
    if "link lost — reconnecting" in x.texto or "bike network lost" in x.texto:
        return "Wi-Fi da moto"
    if "RX watchdog" in x.texto:
        return "painel calado"
    return "Android Auto caiu"


def _grupos_de_queda(bloco):
    """As linhas de queda de uma mesma queda (o Wi-Fi caiu, a rede da moto se perdeu e, até 10 s depois,
    o vigia do painel) são uma queda só; cada grupo é a lista de linhas, a primeira marcando a hora."""
    grupos = []
    for x in bloco:
        if not (any(q in x.texto for q in QUEDAS) and not _nao_e_queda(x, bloco)):
            continue
        if grupos and x.segundos - grupos[-1][-1].segundos <= QUEDA_JUNTA_S:
            grupos[-1].append(x)
        else:
            grupos.append([x])
    return grupos


def _resumo_da_queda(grupo, bloco):
    """A linha de uma queda: a causa de cada sinal e quanto depois veio; mais o Wi-Fi de antes, se o registro o tem."""
    causas = []
    for x in grupo:
        nome = _causa_da_queda(x)
        if nome not in [c for c, _ in causas]:
            causas.append((nome, x.segundos - grupo[0].segundos))
    partes = [nome if atraso < 0.5 else f"{nome} {atraso:.0f} s depois" for nome, atraso in causas]
    volta = _voltou(grupo, bloco)
    if volta:
        # Another channel is the access point restarting; the same one, or none known, only says it came back.
        reiniciou = "→" in volta and "mesmo canal" not in volta
        saida = [f"{grupo[0].hora}  Wi-Fi do painel {'reiniciou' if reiniciou else 'caiu e voltou'} ({volta})"]
    elif _painel_desligou(grupo, bloco):
        saida = [f"{grupo[0].hora}  painel desligou (moto desligada?): o Wi-Fi estava firme e sumiu de uma vez ({', '.join(partes)})"]
    else:
        saida = [f"{grupo[0].hora}  queda ({', '.join(partes)})"]
    painel = _wifi_do_painel(grupo, bloco)
    if painel:
        saida.append(f"{grupo[0].hora}  Wi-Fi do painel antes da queda: {painel}")
    else:
        # The periodic [link] look is the phone's own active Wi-Fi (the home one once the dash is gone): said as such.
        antes = _wifi_antes(grupo, bloco)
        if antes:
            saida.append(f"{grupo[0].hora}  Wi-Fi do celular antes da queda (o do painel não está no registro): {antes[-1].texto.split(' | ')[0]}")
    return saida


def _wifi_do_painel(grupo, bloco):
    """`-74 dBm, 2427 MHz, 58 Mbps (12 s antes)` do último `bike=` das olhadas `[device]` até 90 s antes da queda; vazio sem ele."""
    queda = grupo[0]
    for x in reversed(bloco[:bloco.index(queda)]):
        m = BIKE_DEVICE.search(x.texto) if x.texto.startswith("device ") else None
        if m and queda.segundos - x.segundos <= BIKE_ATE_S:
            mbps = f", {m.group(3)} Mbps" if m.group(3) else ""
            return f"{m.group(1)} dBm, {m.group(2)} MHz{mbps} ({queda.segundos - x.segundos:.0f} s antes)"
    return ""


def _voltou(grupo, bloco):
    """`voltou em 22 s, 2447 → 2427 MHz` se o Wi-Fi do painel voltou em até 5 min da queda (o ponto de acesso do painel reiniciou);
    vazio se não voltou, ou se a queda não é de Wi-Fi. O canal vem do que o registro tem: a linha da perda, o último `bike=` antes,
    a linha da volta ou o primeiro `bike=` depois."""
    # The Wi-Fi's own loss only: the dash-silent watchdog's `… → link lost` can come with the Wi-Fi still up.
    if not any("link lost — reconnecting" in x.texto or "bike network lost" in x.texto for x in grupo):
        return ""
    queda = grupo[0]
    depois = [x for x in bloco if x.segundos > queda.segundos]
    volta = next((x for x in depois if "bike network available" in x.texto
                  or (x.etiqueta == "wifi" and x.texto.startswith(("connected ", "using already-connected WiFi")))), None)
    if volta is None or volta.segundos - queda.segundos > VOLTA_S:
        return ""
    antes = next((m.group(1) for x in grupo for m in [CANAL_ANTES.search(x.texto)] if m), None)
    if antes is None:
        antes = next((m.group(1) for x in reversed(bloco[:bloco.index(queda)]) for m in [CANAL_DEVICE.search(x.texto)] if m), None)
    agora = None
    for x in depois:
        m = CANAL_DEPOIS.search(x.texto)
        # The app writes it as the network comes (before `connected`), so from the loss up to 5 s after the return.
        if m and x.segundos - volta.segundos <= 5:
            agora = m.group(2) or m.group(3)
            break
    if agora is None:
        agora = next((m.group(1) for x in depois if x.segundos >= volta.segundos for m in [CANAL_DEVICE.search(x.texto)] if m), None)
    tempo = f"voltou em {volta.segundos - queda.segundos:.0f} s"
    if antes and agora:
        return f"{tempo}, {antes} → {agora} MHz" if antes != agora else f"{tempo}, mesmo canal {agora} MHz"
    return tempo


def _wifi_antes(grupo, bloco):
    """As linhas `[link]` dos 70 s antes de uma queda (a última é a do Wi-Fi de antes)."""
    return [y for y in bloco if y.etiqueta == "link" and not y.texto.startswith("link at failure")
            and 0 <= grupo[0].segundos - y.segundos <= 70]


def _painel_desligou(grupo, bloco):
    """A queda de um Wi-Fi que estava firme (sinal bom e quase parado nas últimas amostras) e sumiu de uma vez: é o
    painel desligando junto com a moto, não um problema de rádio. Sem o histórico do sinal no registro, não se afirma."""
    if not any("link lost — reconnecting" in x.texto or "bike network lost" in x.texto for x in grupo):
        return False
    if _voltou(grupo, bloco):
        return False  # a dash that comes back within minutes was not switched off for good: its Wi-Fi restarted or hung
    antes = _wifi_antes(grupo, bloco)
    historico = HISTORICO_RSSI.search(antes[-1].texto) if antes else None
    if not historico:
        return False
    amostras = [int(v) for v in historico.group(1).split() if re.fullmatch(r"-?\d+", v)][-AMOSTRAS_RSSI:]
    return bool(amostras) and min(amostras) >= RSSI_FRACO_DBM and max(amostras) - min(amostras) <= RSSI_VARIACAO_DB


def _fim_pedido(x, bloco):
    """O fim da sessão que o próprio REau pediu com o bye (desligar, ou uma sessão nova para os ajustes) até 2 s antes:
    não é queda. Versões antigas do app registravam esse fim como `clean=false` mesmo com o bye respondido."""
    return "AaReceiver: session ended" in x.texto and any(
        BYE in y.texto and 0 <= x.segundos - y.segundos < BYE_S for y in bloco)


def _com_a_tela(x, bloco):
    """O fim da sessão do Android Auto com um evento de tela (`[system] screen on|off|unlocked`, ou a mudança de tela na olhada do
    celular) a até 3 s, antes ou depois: o Android Auto fecha junto com a tela, e o REau reconecta sozinho."""
    return "AaReceiver: session ended" in x.texto and any(
        abs(y.segundos - x.segundos) <= TELA_JUNTO_S
        and ((y.etiqueta == "system" and y.texto in TELA_EVENTOS) or (y.texto.startswith("device ") and "[change: screen]" in y.texto))
        for y in bloco)


def _nao_e_queda(x, bloco):
    return _do_desligamento(x, bloco) or _fim_pedido(x, bloco) or _com_a_tela(x, bloco)


def _fechou_com_a_tela(x, bloco):
    """`Android Auto fechou junto com a tela (reconectou em 1,2 s; imagem nova em 2,8 s)`."""
    depois = bloco[bloco.index(x) + 1:]
    reconectou = next((y for y in depois if "connected to the head unit server" in y.texto), None)
    imagem = next((y for y in depois if SPS in y.texto), None)
    partes = []
    if reconectou:
        partes.append(f"reconectou em {(reconectou.segundos - x.segundos):.1f} s".replace(".", ","))
    if imagem:
        partes.append(f"imagem nova em {(imagem.segundos - x.segundos):.1f} s".replace(".", ","))
    return "Android Auto fechou junto com a tela" + (f" ({'; '.join(partes)})" if partes else "")


def _aviso_calor(texto):
    """A linha do aviso de bateria quente (>= 44 °C; BatteryHeatWatch): quando avisou o piloto e quando a bateria voltou abaixo de 41 °C."""
    m = re.search(r"battery at ([\d.]+) C .*rider told the phone is hot", texto)
    if m:
        return f"aviso de celular quente: bateria a {m.group(1)} °C, piloto avisado"
    m = re.search(r"battery back under ([\d.]+) C \(([\d.]+) C\)", texto)
    if m:
        return f"bateria esfriou ({m.group(2)} °C, abaixo de {m.group(1)} °C): novo aviso possível"
    # Leva 5 (11/11/2026): o teto de quadros que também segue a bateria (chave experimentalHeatCeiling), por degrau.
    m = re.match(r"battery (?:at|down to) ([\d.]+) C: the frame ceiling is held under the (\w+) step \(experiment\)", texto)
    if m:
        return f"teto de quadros preso pela bateria a {m.group(1)} °C, no degrau «{m.group(2)}» do estado térmico (chave experimental)"
    m = re.match(r"battery back to ([\d.]+) C: the frame ceiling no longer follows the battery", texto)
    if m:
        return f"teto de quadros solto: bateria a {m.group(1)} °C (chave experimental)"
    return texto


def _marca_gravacao(texto):
    """A linha que a Marca do piloto escreve sobre a gravação da sessão do Android Auto: guardada (nome do par), cópia no lugar de outra, ou não feita.

    O anel de 100 MB do SessionRecorder se sobrescreve; a marca congela uma cópia `reau_<data>_<hora>_marca[.1].aasession` em sessions/."""
    m = re.match(r"mark (\d+): the session recording is kept as (\S+) \((.*?)\)", texto)
    if m:
        return f"marca {m.group(1)}: gravação da sessão guardada como {m.group(2)} ({m.group(3).split(', ')[0]})"
    m = re.match(r"mark (\d+): (the session recording (?:was not copied|could not be copied)|no session recording is open|keeping the session recording failed)", texto)
    if m:
        return f"marca {m.group(1)}: gravação da sessão NÃO guardada ({m.group(2)})"
    return texto


def _device(texto, campo):
    m = re.search(rf"\b{campo}=([^\s%C]+)", texto)
    return m.group(1) if m else None


def _versoes(linhas):
    """[(arquivo, hora, versão, detalhe)] de cada `==== process start:` com a versão do app (`app 0.3.0-dev+26.11.03-03.30 (29894790, 7eee9cb)`); registro de antes dela não traz."""
    saida = []
    for x in linhas:
        m = tempo.VERSAO_DO_APP.match(x.texto) if x.texto.startswith(tempo.PROCESSO_COMECOU) else None
        if m:
            saida.append((x.arquivo, x.hora, m.group(1), m.group(2) or ""))
    return saida


def _versao_de_cada_projecao(linhas):
    """{id da linha de ligar: 'versão (build)'} pela última linha `process start` antes dela."""
    saida, atual = {}, None
    for x in linhas:
        m = tempo.VERSAO_DO_APP.match(x.texto) if x.texto.startswith(tempo.PROCESSO_COMECOU) else None
        if m:
            atual = f"{m.group(1)} ({m.group(2)})" if m.group(2) else m.group(1)
        elif x.texto.startswith(LIGOU):
            saida[id(x)] = atual
    return saida


def versoes_do_app(linhas):
    """Uma linha por versão do app que abriu nos registros: `app 0.3.0-dev+26.11.03-03.30 (29894790, 7eee9cb): 6 aberturas (ride-….log, …)`; vazio sem versão nos registros."""
    por = {}
    for arquivo, _, versao, detalhe in _versoes(linhas):
        por.setdefault(f"{versao} ({detalhe})" if detalhe else versao, []).append(arquivo)
    return [f"app {v}: {len(a)} {'abertura' if len(a) == 1 else 'aberturas'} ({', '.join(a)})" for v, a in por.items()]


def linha_do_tempo(linhas):
    saida = []
    versoes = _versao_de_cada_projecao(linhas)
    for bloco in projecoes(linhas):
        visto_pedido = _visto_pedido_unido(bloco)
        bateria = [b for x in bloco if x.texto.startswith("device ") for b in [_device(x.texto, "battery")] if b and b != "?"]
        termico_antes = None
        grupos = _grupos_de_queda(bloco)
        quedas_do_bloco = {id(g[0]): g for g in grupos}
        interrupcoes = quedas.interrupcoes(bloco, grupos)
        sem_imagem = {id(i["voltou"]): i for i in interrupcoes if i["voltou"] is not None}
        sem_imagem_final = next((i for i in interrupcoes if i["final"]), None)
        ligado_unido = quedas.ligado_unido(bloco, interrupcoes)
        esperas = {id(e[0]): e for e in _esperas(bloco) if e[1] >= ESPERA_NA_LINHA_S}
        for x in bloco:
            t = x.texto
            if _com_a_tela(x, bloco) and not _do_desligamento(x, bloco) and not _fim_pedido(x, bloco):
                saida.append(f"{x.hora}  {_fechou_com_a_tela(x, bloco)}")
                continue
            if id(x) in quedas_do_bloco:
                saida += _resumo_da_queda(quedas_do_bloco[id(x)], bloco)
            if id(x) in esperas:
                saida.append(f"{x.hora}  {_texto_da_espera(esperas[id(x)])}")
            if id(x) in visto_pedido:
                saida.append(f"{x.hora}  {visto_pedido[id(x)]}")
            if t.startswith(LIGOU):
                saida.append(f"{x.hora}  projeção ligada ({x.arquivo})")
                if versoes.get(id(x)):
                    saida.append(f"{x.hora}  versão do app: {versoes[id(x)]}")
                historia, _ = _servidor_aa(bloco)
                if historia:
                    saida.append(f"{x.hora}  {historia}")
            elif t.startswith(DESLIGOU):
                if sem_imagem_final is not None:
                    saida.append(f"{x.hora}  {quedas.texto_da_interrupcao(sem_imagem_final)}")
                if bateria:
                    saida.append(f"{x.hora}  bateria {bateria[0]}% → {bateria[-1]}%")
                presa = _leitura_presa(bloco)
                if presa:
                    saida.append(f"{x.hora}  {presa}")
                video = _video(bloco)
                if video:
                    saida.append(f"{x.hora}  {video}")
                por_ritmo = _video_por_ritmo(bloco)
                if por_ritmo:
                    saida.append(f"{x.hora}  {por_ritmo}")
                telas = _tela_por_tempo(bloco)
                if telas:
                    saida.append(f"{x.hora}  {telas}")
                saida.append(f"{x.hora}  projeção desligada")
            elif x.etiqueta == "mark" and t.startswith("MARCA DO PILOTO"):
                saida.append(f"{x.hora}  marca do piloto {t.split()[-1]}")
            elif x.etiqueta == "mark":
                saida.append(f"{x.hora}  {_marca_gravacao(t)}")  # what the mark did to the session recording (plan 22)
            elif x.etiqueta == "heat":
                saida.append(f"{x.hora}  {_aviso_calor(t)}")  # the phone-is-hot notice (plan 22)
            elif x.etiqueta == "settings" and t.startswith(("settings in use: ", "connection experiments ON: ")):
                saida.append(f"{x.hora}  {t}")  # the settings the ride was made with, at the start of each projection, and the connection experiments that were on
            elif x.etiqueta == "wifi" and t.startswith("connected "):
                saida.append(f"{x.hora}  painel no Wi-Fi")
                if id(x) in ligado_unido:
                    saida.append(f"{x.hora}  {ligado_unido[id(x)]}")
            elif t.startswith("resumo "):
                saida.append(f"{x.hora}  {t}")
            elif t.startswith("device "):
                termico = _device(t, "thermal")
                if termico_antes is not None and termico != termico_antes:
                    saida.append(f"{x.hora}  estado térmico {termico_antes} → {termico}")
                termico_antes = termico
            elif EPERM_REDE.search(t):
                saida.append(f"{x.hora}  sessão do painel falhou: EPERM ao ligar o socket à rede {EPERM_REDE.search(t).group(1)}")
            elif EPERM_DESCARTADA.search(t):
                m = EPERM_DESCARTADA.search(t)
                dono = " (rede que o próprio celular uniu, não pedido do REau)" if EPERM_REDE_DO_CELULAR in t else ""
                saida.append(f"{x.hora}  rede {m.group(1)} recusada pelo Android (EPERM, {m.group(2)}ª seguida){dono}: o app a descartou")
            elif EPERM_REDE_NO_LUGAR in t:
                saida.append(f"{x.hora}  outra rede ficou no lugar da recusada: o painel é tentado já, sem esperar")
            elif not _nao_e_queda(x, bloco):
                for procura, diz in EVENTOS:
                    if procura in t:
                        if diz.startswith(("Wi-Fi na falha", "o que o app enviava")):
                            saida.append(f"{x.hora}  {diz}: {t[t.index(procura) + len(procura):].strip()}")
                        else:
                            saida.append(f"{x.hora}  {diz}: {t[:110]}" if diz.startswith(("vigia", "o app", "auth:")) else f"{x.hora}  {diz}")
                        break
            if id(x) in sem_imagem:
                saida.append(f"{x.hora}  {quedas.texto_da_interrupcao(sem_imagem[id(x)])}")
            saida += [f"{x.hora}  {novo}" for novo in _eventos_novos(x, bloco)]
    return saida



READ_HELD = re.compile(r"aa-stats ([\d.]+)s: .* readHeld=([\d.]+)/([\d.]+)ms")


def _leitura_presa(bloco):
    """O pior tempo que a leitura do Android Auto ficou presa entregando quadros ao decodificador, numa janela
    das linhas aa-stats e numa vez só; None quando o registro é de antes do campo."""
    janelas = [(x, m) for x in bloco for m in [READ_HELD.search(x.texto)] if m]
    if not janelas:
        return None
    pior_janela = max(janelas, key=lambda j: float(j[1].group(2)))
    pior_vez = max(janelas, key=lambda j: float(j[1].group(3)))
    return (f"leitura do Android Auto presa no decodificador: pior janela {float(pior_janela[1].group(2)):.1f} ms em "
            f"{float(pior_janela[1].group(1)):g} s ({pior_janela[0].hora}), pior vez {float(pior_vez[1].group(3)):.1f} ms "
            f"({pior_vez[0].hora})")


VIDEO_5S = re.compile(r"kbps=(\d+) .*?frame=(\d+)B")
VIDEO_COMPLETA = re.compile(r"full=(true|false)")
VIDEO_IDR = re.compile(r"idr=(\d+)\(max (\d+)B/(\d+)pkt\)")
VIDEO_REDUCAO = re.compile(r"reduction (\w+)")
VIDEO_DESCARTADOS = re.compile(r"(\d+) packets discarded")
VIDEO_ESPERA_TICK = re.compile(r"tickwait=(\d+)/(\d+)/(\d+)ms")
VIDEO_AVISO = re.compile(r"notice 09 06 55 (#\d+), ([\d,.]+) s into")
# The fields at the end of the `video fps` line since 03/11/2026 (plan 23): `compose_max=7.6ms compose_p95=5.0ms encode_max=19.2ms encode_p95=17.0ms
# look=3/0.90/1.40ms latch=152/0.20/0.90ms wake=152(30.4/s)`; `-` / `look=0` when nothing was measured; old lines have none of them.
VIDEO_COMPOSE_MAX = re.compile(r"\bcompose_max=([\d.]+)ms")
VIDEO_COMPOSE_P95 = re.compile(r"\bcompose_p95=([\d.]+)ms")
VIDEO_ENCODE_MAX = re.compile(r"\bencode_max=([\d.]+)ms")
VIDEO_ENCODE_P95 = re.compile(r"\bencode_p95=([\d.]+)ms")
VIDEO_LOOK = re.compile(r"\blook=(\d+)(?:/([\d.]+)/([\d.]+)ms)?")
VIDEO_LATCH = re.compile(r"\blatch=(\d+)(?:/([\d.]+)/([\d.]+)ms)?")
VIDEO_ACORDARES = re.compile(r"\bwake=(\d+)\(([\d.]+)/s\)")
VIDEO_DESENHADOS = re.compile(r"\bdrawn=(\d+)")


def _video(bloco):
    """A qualidade do vídeo de uma projeção, só com o que o registro traz: a taxa média em movimento e a pior janela,
    o quadro médio, os quadros-chave (`idr=`, quando a linha os traz), a redução da imagem, as trocas de perfil de
    navegação, o perfil 2 que limitou o bitrate, os pacotes descartados do início e o primeiro aviso 09 06 55. None sem nenhuma linha de 5 s."""
    janelas = [x for x in bloco if x.texto.startswith("video fps target=")]
    taxas = []
    for x in janelas:
        m = VIDEO_5S.search(x.texto)
        if m:
            cheia = VIDEO_COMPLETA.search(x.texto)
            taxas.append((int(m.group(1)), int(m.group(2)), bool(cheia and cheia.group(1) == "true")))
    if not taxas:
        return None
    em_movimento = [k for k, _, cheia in taxas if cheia] or [k for k, _, _ in taxas]
    partes = [f"kbps médio {sum(em_movimento) / len(em_movimento):.0f} em movimento (pior janela {max(k for k, _, _ in taxas)})",
              f"quadro médio {sum(q for _, q, _ in taxas) / len(taxas):.0f} B"]
    idr = [m for x in janelas for m in [VIDEO_IDR.search(x.texto)] if m]
    if idr:
        partes.append(f"quadros-chave {sum(int(m.group(1)) for m in idr)} (o maior {max(int(m.group(2)) for m in idr)} B "
                      f"em {max(int(m.group(3)) for m in idr)} pacotes)")
        por_ritmo = _quadros_chave_por_ritmo(bloco)
        if por_ritmo:
            partes.append("quadros-chave por segundo " + ", ".join(
                f"{ritmo} fps {taxa:.2f}".replace(".", ",") for ritmo, (taxa, _) in sorted(por_ritmo.items())))
    reducao = [m.group(1) for x in bloco for m in [VIDEO_REDUCAO.search(x.texto)] if m and x.texto.startswith("video loop start")]
    if reducao:
        partes.append(f"redução {reducao[-1]}")
    trocas = sum(1 for x in bloco if x.texto.startswith("nav profile:"))
    if trocas:
        partes.append(f"{trocas} trocas de perfil")
    if any("profile 2 measured" in x.texto for x in bloco):
        partes.append("perfil 2 limitou o bitrate")
    descartados = sum(int(m.group(1)) for x in bloco for m in [VIDEO_DESCARTADOS.search(x.texto)] if m)
    if descartados:
        partes.append(f"{descartados} pacotes descartados")
    espera = [m for x in janelas for m in [VIDEO_ESPERA_TICK.search(x.texto)] if m]
    if espera:
        # The wait of a new picture for the compositor's tick: mean of the windows' means, the worst 95th and the worst.
        partes.append(f"espera pelo tick média {sum(int(m.group(1)) for m in espera) / len(espera):.0f} ms "
                      f"(pior p95 {max(int(m.group(2)) for m in espera)}, máx {max(int(m.group(3)) for m in espera)})")
    partes += _etapas_do_video(janelas)
    aviso = next((m for x in bloco for m in [VIDEO_AVISO.search(x.texto)] if m), None)
    if aviso:
        partes.append(f"aviso 09 06 55 {aviso.group(1)} aos {aviso.group(2)} s")
    return "vídeo: " + ", ".join(partes)


def _etapas_do_video(janelas):
    """Os campos que as linhas `video fps` ganharam no fim em 03/11/2026 (plano 23), em frases para o resumo `vídeo:`: o pior quadro de
    composição e de codificação da projeção (e o pior p95 das janelas), o que custou olhar a imagem (`look`) e travar cada imagem nova
    (`latch`, o `updateTexImage`), e quantas imagens do Android Auto acordaram o compositor por segundo contra quantas foram desenhadas.
    Linhas de antes do campo não entram; sem nenhuma, nada é dito."""
    partes = []
    for nome, maximo, p95 in (("composição", VIDEO_COMPOSE_MAX, VIDEO_COMPOSE_P95), ("codificação", VIDEO_ENCODE_MAX, VIDEO_ENCODE_P95)):
        pior = [float(m.group(1)) for x in janelas for m in [maximo.search(x.texto)] if m]
        if pior:
            p95s = [float(m.group(1)) for x in janelas for m in [p95.search(x.texto)] if m]
            partes.append(f"{nome} pior quadro {tempo.dec(max(pior))} ms (pior p95 {tempo.dec(max(p95s))})" if p95s else f"{nome} pior quadro {tempo.dec(max(pior))} ms")
    for nome, campo in (("leitura da imagem (look)", VIDEO_LOOK), ("updateTexImage", VIDEO_LATCH)):
        achados = [(int(m.group(1)), m) for x in janelas for m in [campo.search(x.texto)] if m]
        feitas = [(n, float(m.group(2)), float(m.group(3))) for n, m in achados if n > 0 and m.group(2)]
        if feitas:
            total = sum(n for n, _, _ in feitas)
            media = sum(n * media for n, media, _ in feitas) / total
            partes.append(f"{nome} {total} vezes, média {tempo.dec(media, 2)} ms (máx {tempo.dec(max(m for _, _, m in feitas), 2)})")
        elif achados:
            partes.append(f"{nome} nenhuma vez")
    acordou = [(int(m.group(1)), float(janela.group(2)), int(desenhados.group(1)))
               for x in janelas for m in [VIDEO_ACORDARES.search(x.texto)] for janela in [VIDEO_JANELA.search(x.texto)]
               for desenhados in [VIDEO_DESENHADOS.search(x.texto)] if m and janela and desenhados]
    segundos = sum(s for _, s, _ in acordou)
    if segundos > 0:
        acordares, desenhos = sum(a for a, _, _ in acordou) / segundos, sum(d for _, _, d in acordou) / segundos
        partes.append(f"{tempo.dec(acordares)} acordares/s do compositor contra {tempo.dec(desenhos)} desenhos/s" +
                      (f" ({tempo.dec(acordares / desenhos)} por desenho)" if desenhos > 0 else ""))
    return partes


def _quadros_chave_por_ritmo(bloco):
    """{ritmo em fps: (quadros-chave por segundo, janelas)} das linhas `video fps target=N … (Kframes/5.1s) … idr=I(max …)`: soma dos
    `idr` sobre soma do tempo das janelas daquele ritmo. Janela sem `idr=`, sem o tempo ou sem `target=` não entra."""
    soma = {}
    for x in bloco:
        if not x.texto.startswith("video fps target="):
            continue
        ritmo, janela, idr = VIDEO_RITMO.search(x.texto), VIDEO_JANELA.search(x.texto), VIDEO_IDR.search(x.texto)
        if not (ritmo and janela and idr):
            continue
        tempo, quadros, n = soma.get(int(ritmo.group(1)), (0.0, 0, 0))
        soma[int(ritmo.group(1))] = (tempo + float(janela.group(2)), quadros + int(idr.group(1)), n + 1)
    return {r: (q / t, n) for r, (t, q, n) in soma.items() if t > 0}


VIDEO_KBPS = re.compile(r"\bkbps=(\d+)")


def _por_ritmo(bloco):
    """{ritmo em fps: (janelas, [idr de cada janela], [kbps de cada janela])} das linhas `video fps target=N … kbps=K … idr=I(max …)`. `idr` só das janelas que o trazem."""
    por = {}
    for x in bloco:
        ritmo = VIDEO_RITMO.search(x.texto) if x.texto.startswith("video fps target=") else None
        if not ritmo:
            continue
        registro = por.setdefault(int(ritmo.group(1)), [0, [], []])
        registro[0] += 1
        idrs, kbps = registro[1], registro[2]
        idr, k = VIDEO_IDR.search(x.texto), VIDEO_KBPS.search(x.texto)
        if idr:
            idrs.append(int(idr.group(1)))
        if k:
            kbps.append(int(k.group(1)))
    return {r: (n, idrs, kbps) for r, (n, idrs, kbps) in por.items()}


def _video_por_ritmo(bloco):
    """`vídeo por ritmo: 2 fps (78 janelas) idr/janela 4,97 (4 a 6), kbps médio 93, máx 270; 4 fps (46 janelas) …` (cada ritmo à parte: o quadro-chave e o
    bitrate de 2, 4 e 6 fps não se misturam). Vazio sem nenhuma linha `video fps`."""
    partes = []
    for ritmo, (n, idrs, kbps) in sorted(_por_ritmo(bloco).items()):
        campos = []
        if idrs:
            campos.append(f"idr/janela {tempo.dec(sum(idrs) / len(idrs), 2)} ({min(idrs)} a {max(idrs)})")
        if kbps:
            campos.append(f"kbps médio {sum(kbps) / len(kbps):.0f}, máx {max(kbps)}")
        partes.append(f"{ritmo} fps ({n} {'janela' if n == 1 else 'janelas'}) " + ", ".join(campos) if campos else f"{ritmo} fps ({n} {'janela' if n == 1 else 'janelas'})")
    return "vídeo por ritmo: " + "; ".join(partes) if partes else ""


def _quadros_chave_altos(bloco):
    """A 6 fps o codificador conta o intervalo em quadros (`fpsHint` 4): o quadro-chave de 1 s vira 0,67 s e passa de 1,2 por segundo
    (01/11/2026: 1,49/s). Some depois da correção do intervalo."""
    taxa, janelas = _quadros_chave_por_ritmo(bloco).get(6, (0.0, 0))
    if janelas < QUADROS_CHAVE_JANELAS_MIN or taxa <= QUADROS_CHAVE_6FPS_MAX:
        return []
    return [f"projeção de {bloco[0].hora} ({bloco[0].arquivo}): quadros-chave a {f'{taxa:.2f}'.replace('.', ',')}/s a 6 fps em {janelas} janelas "
            f"(o esperado é 1 por segundo, no máximo {str(QUADROS_CHAVE_6FPS_MAX).replace('.', ',')}); o codificador conta o intervalo em quadros do fpsHint"]


def _telas(bloco):
    """As telas do Android Auto da projeção, de cada linha `aa-screen: nav|menu|apps …` (a troca ou a confirmação) até a próxima
    troca ou o fim da projeção: [(tela, primeira linha, fim em s)]. A mesma tela confirmada de novo continua a mesma. Vazio sem nenhuma."""
    marcas = [(x, m.group(1)) for x in bloco if x.etiqueta == "aa-screen" for m in [TELA_AA.match(x.texto)] if m]
    segmentos = []
    for x, tela in marcas:
        if segmentos and segmentos[-1][0] == tela:
            continue
        segmentos.append((tela, x))
    fim = bloco[-1].segundos
    saida = []
    for i, (tela, x) in enumerate(segmentos):
        ate = segmentos[i + 1][1].segundos if i + 1 < len(segmentos) else fim
        saida.append((tela, x, ate))
    return saida


def _tela_por_tempo(bloco):
    """`tela por tempo: mapa 12 min 03 s (60%), menu 3 min 00 s (15%), apps 5 min 00 s (25%)`; vazio sem eventos `aa-screen`."""
    soma = {}
    for tela, x, ate in _telas(bloco):
        soma[tela] = soma.get(tela, 0.0) + tempo.dif(ate, x.segundos)
    total = sum(soma.values())
    if total <= 0:
        return ""
    return "tela por tempo: " + ", ".join(
        f"{TELA_NOME[t]} {tempo.duracao_texto(s)} ({100 * s / total:.0f}%)" for t, s in sorted(soma.items(), key=lambda p: -p[1]))


def _apps_sem_tecla(bloco):
    """A tela "apps" que a leitura da imagem escolheu (não uma tecla do painel) e ficou por mais de 2 min com a rota ativa (mensagens
    `Nav: position|state` chegando) sem nenhuma tecla do painel (nem toque no celular) nesse tempo (01/11/2026, 18:47:36 a 19:18: o app leu o mapa como app;
    teve uma tecla do joystick sem efeito no meio, por isso vale o trecho sem tecla, não a tela inteira). Quem entrou em "apps" por tecla
    (`apps (a select in the menu)`) é o dono num app de verdade: não conta."""
    teclas = [x.segundos for x in bloco if (x.etiqueta == "dash" and TECLA_DO_PAINEL.match(x.texto) and TECLA_AVISO not in x.texto)
              or TOQUE_NO_CELULAR in x.texto]
    saida = []
    for tela, x, ate in _telas(bloco):
        if tela != "apps" or "by the picture" not in x.texto:
            continue
        inicio = x.segundos
        fim = inicio + tempo.dif(ate, inicio)
        pontos = [inicio] + sorted(t for t in teclas if inicio < t < fim) + [fim]
        # The longest stretch with no key; the Nav messages that came in it.
        a, b = max(zip(pontos, pontos[1:]), key=lambda p: p[1] - p[0])
        navs = [y for y in bloco if a <= y.segundos <= b and NAV_CHEGANDO.search(y.texto)]
        if b - a > APPS_SEM_TECLA_S and navs:
            saida.append(f"{x.hora}: tela \"apps\" por {tempo.duracao_texto(fim - inicio)}, {tempo.duracao_texto(b - a)} deles sem nenhuma tecla do painel (nem toque no celular) e com a "
                         f"rota ativa ({len(navs)} mensagens Nav:): a leitura da imagem provavelmente tomou o mapa por um app")
    return saida


STALE_FOLGA_S = 2  # the scan's `seen N s ago` is in whole seconds, and the drop's line comes a moment after the loss


def _pedidos_com_painel(bloco):
    """Os pedidos de rede do painel que viram o painel na varredura (`the dash: … seen N s ago`): para cada um, o pedido, os N s, a última olhada anterior
    que não o viu (um pedido sem ele na varredura, ou `scan lookup … → not found` da espera; None se o app nunca olhou sem vê-lo desde o último `connected`)
    e o `connected` que veio depois (None se não veio). Só o primeiro pedido que o vê depois de cada `connected`: os seguintes são o painel recusando."""
    saida, anterior, ja_visto, queda = [], None, False, None
    for i, x in enumerate(bloco):
        if x.etiqueta != "wifi":
            continue
        if "link lost — reconnecting" in x.texto:
            queda = x.segundos
        elif PROCURA_SEM_ACHAR.search(x.texto):
            anterior, ja_visto = x, False
        elif x.texto.startswith(("connected ", "using already-connected WiFi")):
            anterior, ja_visto, queda = None, False, None
        elif PEDIDO_DE_REDE.search(x.texto):
            visto = PEDIDO_COM_PAINEL.search(x.texto)
            if not visto:
                if PAINEL_FORA_DA_VARREDURA in x.texto:
                    anterior, ja_visto = x, False
                continue
            # A sighting older than the drop is the dash that had just gone (the scan list remembers it: `seen 4 s ago` for a dash switched off): no measure of anything
            # (the 8 drops of the 07/11 collection read "383,7 s" and the like), and it does not use up the sighting that is the dash coming back.
            if queda is not None and int(visto.group(2)) >= tempo.dif(x.segundos, queda) - STALE_FOLGA_S:
                continue
            if not ja_visto:
                unido = next((y for y in bloco[i + 1:] if y.etiqueta == "wifi" and y.texto.startswith(("connected ", "using already-connected WiFi"))), None)
                saida.append((x, int(visto.group(2)), anterior, unido))
            ja_visto = True
    return saida


OLHADA_COM_PAINEL = re.compile(r"scan look: the dash is in the list of \d+ networks, its result (\d+) s old")
OLHADA_ATE_S = 30.0


def _olhada_do_app(pedido, bloco):
    """A primeira olhada da vigia que viu o painel (`scan look: the dash is in the list … its result N s old`, a linha do app de 08/11/2026) nos 30 s antes do pedido: separa o tempo
    do Android (o painel na varredura há N s quando o app olhou) do do app (da olhada ao pedido). Vazio sem a linha."""
    antes = [y for y in bloco[:bloco.index(pedido)] if pedido.segundos - y.segundos <= OLHADA_ATE_S and 0 <= pedido.segundos - y.segundos]
    primeira = next((y for y in antes for m in [OLHADA_COM_PAINEL.search(y.texto)] if m and y.etiqueta == "wifi"), None)
    if primeira is None:
        return ""
    n = int(OLHADA_COM_PAINEL.search(primeira.texto).group(1))
    return (f"olhada do app {tempo.hora_ms(primeira.segundos)} com o painel na varredura há {n} s (visto pelo Android cerca de {tempo.hora_ms(primeira.segundos - n)}); "
            f"do visto à olhada {n} s, da olhada ao pedido {tempo.dec(pedido.segundos - primeira.segundos)} s")


def _visto_pedido_unido(bloco):
    """{id da linha do pedido: texto} de `painel visto → pedido → unido`, medido pelo `seen N s ago` da varredura (a hora em que o Android viu o painel é a do
    pedido menos N s, ao segundo) e não pela hora em que o app olhou: 03/11/2026, painel visto às 07:44:46,8 e pedido às 07:44:59,8."""
    saida = {}
    for pedido, visto_s, _, unido in _pedidos_com_painel(bloco):
        partes = [f"visto há {visto_s} s no pedido #{PEDIDO_DE_REDE.search(pedido.texto).group(1)} (cerca de {tempo.hora_ms(pedido.segundos - visto_s)})",
                  f"pedido {tempo.hora_ms(pedido.segundos)}"]
        olhada = _olhada_do_app(pedido, bloco)
        if olhada:
            partes.append(olhada)
        if unido is not None:
            ate = tempo.dif(unido.segundos, pedido.segundos)
            partes.append(f"unido {tempo.hora_ms(unido.segundos)} (pedido→unido {tempo.dec(ate)} s; visto→unido {tempo.dec(ate + visto_s)} s)")
        else:
            partes.append("sem unir até o fim da projeção")
        saida[id(pedido)] = "painel visto → pedido → unido: " + ", ".join(partes)
    return saida


def _painel_visto_tarde(bloco):
    """O painel apareceu na varredura há mais de 10 s e o pedido de rede só saiu agora. Vale o pedido que vê o painel (`the dash: … seen N s ago`, N > 10) depois de uma
    olhada do app que não o viu: um pedido anterior sem ele na varredura (01/11/2026, 18:45:57 visto, 18:46:08 pedido: a pausa entre pedidos) ou a olhada da própria espera
    (`scan lookup … → not found`, 03/11/2026: visto às 07:44:46,8, pedido às 07:44:59,8; a pausa de 30 s só foi cortada quando a tela acendeu). Sem olhada anterior sem vê-lo
    (o primeiro pedido da projeção) ou com o painel já visto no pedido anterior (recusando) não é atraso do app."""
    saida = []
    for pedido, visto_s, anterior, unido in _pedidos_com_painel(bloco):
        if visto_s <= APP_TARDE_S or anterior is None:
            continue
        depois = f"{pedido.segundos - anterior.segundos:.0f} s depois do anterior" if PEDIDO_DE_REDE.search(anterior.texto) \
            else f"{pedido.segundos - anterior.segundos:.0f} s depois da última olhada sem vê-lo"
        n = PEDIDO_DE_REDE.search(pedido.texto).group(1)
        medida = f"; visto→pedido {visto_s} s" + (f", pedido→unido {tempo.dec(tempo.dif(unido.segundos, pedido.segundos))} s" if unido is not None else "")
        saida.append(f"{pedido.hora}: painel visto na varredura há {visto_s} s e o app só pediu a rede agora (pedido #{n}, {depois}): "
                     f"a pausa entre as olhadas segurou o app{medida}")
    return saida


def problemas(linhas):
    saida = []
    for bloco in projecoes(linhas):
        inicio = f"{bloco[0].hora} ({bloco[0].arquivo})"
        textos = [x.texto for x in bloco]
        # The dash's own 10 s mark (09 06 55) counts in any state: it comes with no video at all (20/10/2026), so it is no first frame
        # and its absence is only the stream being shorter than 10 s.
        if _transmitiu_ate_o_aviso(bloco) and not any(m in t for t in textos for m in AVISO_DE_10S):
            saida.append(f"projeção de {inicio}: transmitindo sem confirmação do primeiro quadro pelo painel")
        for x in bloco:
            # The RX watchdog is the echo of a drop (the dash went off with the bike, its Wi-Fi went, or it fell silent): every drop is treated the same, none is a
            # problem for the watchdog's sake (item 17a: it was told in 4 of 16 drops, by what the Wi-Fi history happened to look like). The drops are in the timeline.
            if ("watchdog:" in x.texto and "RX watchdog" not in x.texto) or "Decoder restart requested" in x.texto:
                saida.append(f"{x.hora}: vigia — {x.texto[:120]}")
            if "the app crashed" in x.texto:
                saida.append(f"{x.hora}: o app travou — {x.texto[:120]}")
            if "Auth timed out" in x.texto and x.nivel == "E":
                saida += _autenticacao_falhou(x, bloco)
            reenvios = CHAVE_CHEGOU.search(x.texto)
            if reenvios and int(reenvios.group(2)) >= 2:
                saida.append(f"{x.hora}: a chave do painel só veio depois de {reenvios.group(2)} reenvios da saudação ({reenvios.group(1)} s)")
            if "unavailable after" in x.texto and "seenInScan=true" in x.texto:
                saida.append(f"{x.hora}: pedido de rede expirou com o painel na varredura (seenInScan=true)")
        # A wait in which the dash never showed in the scan is the bike still switched off (or not yet on): no defect.
        saida += [f"{e[0].hora}: laço de espera pelo painel — {e[2]} pedidos de rede em {tempo.duracao_texto(e[1])}"
                  for e in _esperas(bloco) if e[1] >= ESPERA_PROBLEMA_S and _painel_apareceu(e)]
        saida += quedas.problemas_das_interrupcoes(quedas.interrupcoes(bloco, _grupos_de_queda(bloco)))
        saida += _laco_de_reconexao(bloco)
        saida += _sem_imagem(bloco) + _buracos(bloco) + _quedas(bloco) + _temperatura(bloco) + _sem_resposta(bloco)
        saida += _painel_travado(bloco) + _eperm_repetido(bloco) + _eperm_espera(bloco)
        saida += _envio_por_politica(bloco)
        saida += _apps_sem_tecla(bloco) + _quadros_chave_altos(bloco) + _painel_visto_tarde(bloco)
        saida += [f"projeção de {inicio}: servidor do AA — {causa}" for causa in _servidor_aa(bloco)[1]]
    # The test of item 1a (the Wi-Fi list with the app behind) fails here, with the reasons; its whole account is in `teste_1a`.
    return saida + varredura.problemas_do_teste(varredura.teste_1a(linhas)) + olhadas.problemas(projecoes(linhas))


AUTENTICACAO_SINAL = re.compile(r"rssi[ =](-\d+)")
AUTENTICACAO_ATE_S = 90.0


def _autenticacao_falhou(x, bloco):
    """O `Auth timed out` do registro, como problema só quando diz alguma coisa do painel ou do app. O de "not a single packet from the dash (bike off, or its Wi-Fi gone?)"
    é a moto desligada (07/11 08:18:44 e 19:41:06): não é problema. O que diz "link is weak" (build de 08/11 em diante) é o sinal fraco e vai assim. O antigo que manda
    conferir o nome (`Check SSID`) com o sinal de -75 dBm ou menos pouco antes (a linha `link lost` ou a olhada do Wi-Fi) é o mesmo caso com o texto enganoso (05/11 07:13:21, -80 dBm:
    o painel estava ligado e certo)."""
    texto = x.texto[x.texto.index("Auth timed out"):]
    if "not a single packet" in texto:
        return []
    if "link is weak" in texto:
        return [f"{x.hora}: autenticação do painel falhou com o sinal fraco — {texto[:150]}"]
    antes = [y for y in reversed(bloco[:bloco.index(x)]) if 0 <= x.segundos - y.segundos <= AUTENTICACAO_ATE_S]
    sinal = next((int(m.group(1)) for y in antes for m in [AUTENTICACAO_SINAL.search(y.texto)] if m and y.etiqueta in ("wifi", "link")), None)
    if "Check SSID" in texto and sinal is not None and sinal <= TRAVADO_SINAL_DBM:
        return [f"{x.hora}: autenticação do painel falhou com o sinal fraco ({sinal} dBm pouco antes): o texto `Check SSID` era enganoso, o painel estava longe, não com o nome errado — "
                f"{texto[:110]}"]
    return [f"{x.hora}: autenticação do painel falhou — {texto[:110]}"]


def _transmitiu_ate_o_aviso(bloco):
    """A projeção transmitiu (`→ STREAMING`) e durou 10 s ou mais depois disso: só então o painel já teria mandado o aviso dos 10 s
    (09 06 55). Uma projeção desligada com 8 s (31/10/2026, 10:11:29) não tem o que confirmar."""
    inicio = next((x for x in bloco if "→ STREAMING" in x.texto), None)
    return inicio is not None and tempo.dif(bloco[-1].segundos, inicio.segundos) >= PROJECAO_CURTA_S


def _apps_provisorio_desmentido(bloco):
    """O `aa-screen: apps (a select in the menu)` que a imagem desmente logo depois (`menu (by the picture: …, from apps)` em até 5 s): o toque no menu mudou a
    tela para "apps" sem olhar a imagem e o painel mostrou o recorte dos apps com o menu ainda na tela (03/11/2026: 07:04:34,0 por 1,1 s e 07:57:20,9 por 1,0 s,
    2 de 5). Devolve [(linha da seleção, segundos até a imagem desmentir)] e o total de seleções."""
    eventos = [x for x in bloco if x.etiqueta == "aa-screen" and x.texto.startswith("aa-screen:")]
    selecoes = [(i, x) for i, x in enumerate(eventos) if x.texto.startswith(APPS_PROVISORIO)]
    desmentidos = []
    for i, x in selecoes:
        proximo = eventos[i + 1] if i + 1 < len(eventos) else None
        if proximo is not None and MENU_PELA_IMAGEM_DE_APPS.match(proximo.texto):
            dt = tempo.dif(proximo.segundos, x.segundos)
            if dt <= APPS_DESMENTIDO_S:
                desmentidos.append((x, dt))
    return desmentidos, len(selecoes)


MAPA_PELA_IMAGEM_DE_APPS = re.compile(r"^aa-screen: nav \(by the picture: .*from apps\)")


def _apps_ate_o_mapa(bloco):
    """A escolha no menu que a imagem leva ao mapa (`nav (by the picture: a map, from apps)` em até 5 s do `apps (a select in the menu)`: o dono escolheu o Waze ou o Google Maps): o
    painel mostra o recorte dos apps por 0,3 a 1 s antes de o mapa confirmar (07/11 15:12:20 por 0,6 s e 06/11 17:27:34 por 0,9 s). Não é "desmentido" (o app escolhido é de navegação);
    fica contado, abaixo de 1 s também. Devolve [(linha da seleção, segundos)] e o total de seleções."""
    eventos = [x for x in bloco if x.etiqueta == "aa-screen" and x.texto.startswith("aa-screen:")]
    selecoes = [(i, x) for i, x in enumerate(eventos) if x.texto.startswith(APPS_PROVISORIO)]
    ao_mapa = []
    for i, x in selecoes:
        proximo = eventos[i + 1] if i + 1 < len(eventos) else None
        if proximo is not None and MAPA_PELA_IMAGEM_DE_APPS.match(proximo.texto):
            dt = tempo.dif(proximo.segundos, x.segundos)
            if dt <= APPS_DESMENTIDO_S:
                ao_mapa.append((x, dt))
    return ao_mapa, len(selecoes)


def _apps_provisorio(bloco):
    saida = []
    desmentidos, total = _apps_provisorio_desmentido(bloco)
    if desmentidos:
        quando = "; ".join(f"{x.hora[:10].replace('.', ',')} por {tempo.dec(dt)} s" for x, dt in desmentidos)
        saida.append(f"projeção de {bloco[0].hora} ({bloco[0].arquivo}): \"apps\" provisório desmentido pela imagem {len(desmentidos)} de {total} vezes ({quando}): "
                     "a seleção no menu trocou a tela para apps sem olhar a imagem, e o painel mostraria o recorte dos apps com o menu ainda na tela")
    ao_mapa, total = _apps_ate_o_mapa(bloco)
    if ao_mapa:
        quando = "; ".join(f"{x.hora[:10].replace('.', ',')} por {tempo.dec(dt)} s" for x, dt in ao_mapa)
        saida.append(f"projeção de {bloco[0].hora} ({bloco[0].arquivo}): a escolha no menu levou ao mapa {len(ao_mapa)} de {total} vezes ({quando}): o painel mostraria o recorte dos apps "
                     "por esse tempo antes de a imagem confirmar o mapa (o app escolhido era de navegação; só informativo)")
    return saida


def _painel_travado(bloco):
    """O painel que fica na varredura com sinal bom e recusa todos os pedidos de rede, depois de ter falado: o Wi-Fi dele travou
    (01/11/2026, 19:12 a 19:16; o resto do painel funcionava, só a imagem sumiu) e só desligar e ligar a ignição resolve.
    Com a linha do app (`dash visible but refusing for N s (K tries…)`) é ela que vale; sem ela (registro de antes de 02/11/2026) a
    conclusão sai das linhas `unavailable … (mid-session` com o painel na varredura a -75 dBm ou melhor, duas ou mais, passados 30 s da queda."""
    saida = []
    for x in bloco:
        m = PAINEL_TRAVADO.search(x.texto)
        if m:
            saida.append(f"{x.hora}: painel travado — visível na varredura mas recusando há {m.group(1)} s ({m.group(2)} tentativas); "
                         "o Wi-Fi do painel provavelmente travou, só desligar e ligar a ignição resolve")
    if saida:
        return saida
    for grupo in _grupos_de_queda(bloco):
        if not any("link lost — reconnecting" in x.texto or "bike network lost" in x.texto for x in grupo):
            continue
        queda = grupo[0]
        # The absence ends at the next time the dash's Wi-Fi is got.
        fim = next((y for y in bloco if y.segundos > queda.segundos and y.etiqueta == "wifi"
                    and y.texto.startswith(("connected ", "using already-connected WiFi"))), None)
        recusas = [(y, int(m.group(1)), int(m.group(2))) for y in bloco
                   if y.segundos > queda.segundos and (fim is None or y.segundos < fim.segundos)
                   for m in [RECUSA_COM_PAINEL.search(y.texto)] if m]
        boas = [(y, dbm) for y, dbm, visto in recusas if dbm >= TRAVADO_SINAL_DBM and visto <= TRAVADO_VISTO_S]
        if len(boas) >= 2 and boas[-1][0].segundos - queda.segundos >= TRAVADO_MIN_S and not any(visto > 60 for _, _, visto in recusas):
            melhor = max(dbm for _, dbm in boas)
            saida.append(f"{queda.hora}: painel travado (pelo registro antigo, sem a linha do app) — a varredura mostrou o painel a {melhor} dBm "
                         f"em {len(boas)} pedidos recusados, até {boas[-1][0].hora} ({boas[-1][0].segundos - queda.segundos:.0f} s depois da queda); "
                         "o Wi-Fi do painel provavelmente travou, só desligar e ligar a ignição resolve")
    return saida


def _eperm_repetido(bloco):
    """`Binding socket to network N failed: EPERM` duas vezes ou mais com o mesmo N: o app reaproveitou uma rede que o Android recusa
    (31/10/2026, 10 vezes em 4,5 min na rede 454, até o piloto desligar). E o app que descarta a rede e pede outra, mas a nova também é
    recusada, `EPERM_SEGUIDOS_PROBLEMA` vezes seguidas."""
    saida, por_rede = [], {}
    for x in bloco:
        m = EPERM_REDE.search(x.texto)
        if m:
            por_rede.setdefault(m.group(1), []).append(x)
    for rede, vezes in por_rede.items():
        if len(vezes) >= 2:
            saida.append(f"{vezes[0].hora}: EPERM repetido na mesma rede (network={rede}, {len(vezes)} vezes em "
                         f"{vezes[-1].segundos - vezes[0].segundos:.0f} s, até {vezes[-1].hora}): o app reaproveitou uma rede morta ou recusada pelo Android")
    for x in bloco:
        m = EPERM_DESCARTADA.search(x.texto)
        if m and int(m.group(2)) >= EPERM_SEGUIDOS_PROBLEMA:
            saida.append(f"{x.hora}: EPERM em {m.group(2)} redes seguidas (a última, {m.group(1)}): o Android recusa o socket mesmo em rede nova "
                         "(VPN ou rede restrita?)")
    return saida


def _eperm_espera(bloco):
    """Um EPERM, outra rede unida logo depois (`using already-connected WiFi` ou `connected`) e, mesmo assim, o painel só tentado depois de uma
    espera de `EPERM_ESPERA_S` s ou mais (06/11/2026 17:47:21: 30 s, ~35 s a mais sem imagem), sem o corte "the dash is tried now" nos
    `EPERM_CORTE_JANELA_S` s seguintes. Com a correção de 08/11 o corte vem 2 s depois da recusa e isto não aparece."""
    saida = []
    for i, x in enumerate(bloco):
        if not EPERM_DESCARTADA.search(x.texto):
            continue
        depois = [y for y in bloco[i + 1:] if y.segundos - x.segundos <= EPERM_JANELA_S + EPERM_CORTE_JANELA_S + EPERM_ESPERA_S]
        unida = next((y for y in depois if y.etiqueta == "wifi" and y.texto.startswith(("connected ", "using already-connected WiFi"))
                      and y.segundos - x.segundos <= EPERM_JANELA_S), None)
        espera = next((y for y in depois if EPERM_ESPERA.search(y.texto) and y.segundos - x.segundos <= EPERM_JANELA_S), None)
        if unida is None or espera is None:
            continue
        segundos = int(EPERM_ESPERA.search(espera.texto).group(1))
        cortada = any("the dash is tried now, not when the wait ends" in y.texto and 0 <= y.segundos - espera.segundos <= EPERM_CORTE_JANELA_S
                      for y in depois)
        if segundos >= EPERM_ESPERA_S and not cortada:
            saida.append(f"{x.hora}: EPERM e, {unida.segundos - x.segundos:.0f} s depois, outra rede já unida, mas o painel só foi tentado {segundos} s "
                         f"depois ({espera.hora}): ~{segundos} s a mais sem imagem")
    return saida


def _sem_imagem(bloco):
    """Cada vez que a imagem do painel parou por mais de 10 s e voltou, calculada de `video loop stop` ao próximo `→ STREAMING` quando o
    resumo não traz o `dark=` (registros antigos). Com o `dark=`, é ele que conta (ver `_video`)."""
    if any(x.texto.startswith("resumo ") and DARK.search(x.texto) for x in bloco):
        return []
    saida, parou = [], None
    for x in bloco:
        if "video loop stop" in x.texto:
            parou = x
        elif "→ STREAMING" in x.texto and parou is not None:
            if x.segundos - parou.segundos > DARK_PROBLEMA_S:
                saida.append(f"{x.hora}: painel sem imagem — {x.segundos - parou.segundos:.0f} s entre o vídeo parar ({parou.hora}) e voltar")
            parou = None
    return saida


def _esperas(bloco):
    """Os laços de espera pelo painel: pedidos do Wi-Fi dele (`requestNetwork`) um atrás do outro, sem um `connected` (ou `using already-connected WiFi`) no meio.
    Cada um é (primeira linha, duração em s, quantos pedidos, as linhas do Wi-Fi do laço); a duração vai do primeiro pedido ao
    `connected` (ou ao último pedido, se a projeção acabou esperando)."""
    saida, pedidos, wifi = [], [], []

    def fecha(fim):
        if pedidos:
            # The clock of a line is the time of day: a wait that crosses midnight goes back, and is a day longer on the other side.
            saida.append((pedidos[0], tempo.dif((fim or pedidos[-1]).segundos, pedidos[0].segundos), len(pedidos), list(wifi)))
            pedidos.clear()
        wifi.clear()

    for x in bloco:
        if x.etiqueta == "wifi" and PEDIDO_DE_REDE.search(x.texto):
            pedidos.append(x)
            wifi.append(x)
        elif x.etiqueta == "wifi" and x.texto.startswith(("connected ", "using already-connected WiFi")):
            fecha(x)
        elif x.etiqueta == "wifi" and pedidos:
            wifi.append(x)
    fecha(None)
    return saida


def _vezes_visto(espera):
    """Quantas vezes o painel esteve na varredura durante o laço de espera: os pedidos que o veem (`the dash: '…' -57 dBm … seen N s ago`)
    mais os que expiraram com `seenInScan=true`; None num registro sem a linha da varredura (antigo), que não diz."""
    _, _, _, linhas = espera
    pedidos = [x for x in linhas if PEDIDO_DE_REDE.search(x.texto)]
    if not any("scan of" in x.texto for x in pedidos):
        return None
    return sum(1 for x in pedidos if PEDIDO_COM_PAINEL.search(x.texto)) + sum(1 for x in linhas if "seenInScan=true" in x.texto)


def _painel_apareceu(espera):
    """O painel ficou na varredura durante o laço de espera sem ser unido: dois pedidos seguidos o veem (ou um pedido expirou com `seenInScan=true`). Um laço cujos pedidos
    todos dizem `the dash not among them` é a moto desligada (ou ainda por ligar); o painel visto só no último pedido é a moto que ligou no fim da espera; e o visto só no
    primeiro pedido, logo depois de uma queda, é a lembrança do painel que acabou de sair (`seen 4 s ago` de um painel desligado): 06/11 17:21:01 e 07/11 15:03:50, os dois
    voltaram. Registro sem a linha da varredura (antigo) não diz: conta como apareceu, para não esconder um defeito."""
    if _vezes_visto(espera) is None or any("seenInScan=true" in x.texto for x in espera[3]):
        return True
    pedidos = [x for x in espera[3] if PEDIDO_DE_REDE.search(x.texto)]
    vistos = [bool(PEDIDO_COM_PAINEL.search(x.texto)) for x in pedidos]
    fim = espera[0].segundos + espera[1]
    # Two requests in a row with the dash in the scan, and the wait still going `ESPERA_PAINEL_VISTO_S` s after the first of them (15:10:31 and 15:10:35 on 07/11 saw the dash
    # coming back and joined 5 s later: the bike was on at the end, not a hung Wi-Fi).
    return any(a and b and tempo.dif(fim, pedidos[k].segundos) >= ESPERA_PAINEL_VISTO_S for k, (a, b) in enumerate(zip(vistos, vistos[1:])))


def _texto_da_espera(espera):
    """`espera pelo painel: 25 pedidos de rede em 17 min 53 s sem achá-lo`"""
    _, duracao, pedidos, _ = espera
    nunca = " (o painel não apareceu na varredura: moto desligada?)" if _vezes_visto(espera) == 0 else ""
    return f"espera pelo painel: {pedidos} pedidos de rede em {tempo.duracao_texto(duracao)}{nunca}"


def _ms(a, b):
    return round((b.segundos - a.segundos) * 1000)


def _servidor_aa(bloco):
    """A história de uma caminhada que liga o servidor do Android Auto por Ligar, e as causas que o registro mostra.

    Devolve (linha, causas): a linha com os intervalos toque → início da caminhada → clique em Iniciar → Voltar → fim →
    primeira conexão (só os que o registro tem), e as causas (porta fechada em 5 s; servidor que só atendeu quando a tela do
    Android Auto abriu; menu lido pela metade). ("", []) sem nenhuma linha `start walk begins` (sem o serviço de acessibilidade não há caminhada).
    """
    inicio = next((x for x in bloco if SERVIDOR_INICIO in x.texto), None)
    if inicio is None:
        return "", []
    depois = bloco[bloco.index(inicio):]
    # The first walk is the story of this Ligar: its click and its Back are looked for up to its end, not in a later walk.
    fim = next((x for x in depois if SERVIDOR_FIM in x.texto), None)
    caminhada = depois[:depois.index(fim) + 1] if fim else depois
    clique = next((x for x in caminhada if SERVIDOR_CLIQUE in x.texto), None)
    voltar = next((x for x in caminhada[caminhada.index(clique):] if SERVIDOR_VOLTAR.search(x.texto)), None) if clique else None
    conectou = next((x for x in depois if SERVIDOR_CONECTOU in x.texto), None)
    toque = next((x for x in bloco if SERVIDOR_TOQUE in x.texto), None)
    if toque is None and inicio.segundos - bloco[0].segundos <= SERVIDOR_TOQUE_PERTO_S:
        toque = bloco[0]  # no line for the tap: the mark of the projection, when it is right before the walk
    passos = [("toque", toque), ("início da caminhada", inicio), ("clique em Iniciar", clique), ("Voltar", voltar),
              ("fim da caminhada", fim), ("primeira conexão", conectou)]
    presentes = []
    for nome, x in passos:
        if x is not None and (not presentes or x.segundos >= presentes[-1][1].segundos):
            presentes.append((nome, x))  # a step that came before the one kept (the connection came during the walk) is left out
    partes = [presentes[0][0]] + [f"{nome} +{_ms(anterior, x)} ms" for (_, anterior), (nome, x) in zip(presentes, presentes[1:])]
    causas = []
    if any(SERVIDOR_PORTA_FECHADA in x.texto for x in depois):
        causas.append("toque ok, porta fechada em 5 s")
    for x in depois:
        if SERVIDOR_CONECTOU not in x.texto:
            continue
        abriu = next((y for y in reversed(depois[:depois.index(x)]) if any(k in y.texto for k in SERVIDOR_ABRIU)), None)
        if abriu is None or x.segundos - abriu.segundos >= SERVIDOR_ATENDEU_APOS_S:
            continue
        if not any(SERVIDOR_CLIQUE in y.texto for y in depois[depois.index(abriu):depois.index(x)]):
            causas.append(f"servidor atendeu {_ms(abriu, x)} ms depois de a tela do AA abrir")
    for x in depois:
        menu = SERVIDOR_MENU.search(x.texto)
        if "DEVELOPER_MODE_OFF" in x.texto and menu and len(menu.group(1).split(" | ")) <= SERVIDOR_MENU_PELA_METADE:
            causas.append("menu pela metade (AA frio?)")
    if any(SERVIDOR_VISITA_OK in x.texto for x in depois):
        causas.append("a visita à tela do AA subiu o servidor")
    elif any(SERVIDOR_VISITA_NAO in x.texto for x in depois):
        causas.append("a visita à tela do AA não subiu o servidor")
    return "servidor do AA: " + " → ".join(partes) + (f" ({'; '.join(causas)})" if causas else ""), causas


def _sem_resposta(bloco):
    """O Android Auto calado (a leitura desistiu em 15 s) e o que aceita a conexão e não responde."""
    saida = [f"{x.hora}: Android Auto calado, 15 s sem mandar nada (a imagem do painel parou)" for x in bloco if CALADO in x.texto]
    seguidas, inicio = 0, None
    for x in bloco:
        if NEGOCIACAO_FALHOU in x.texto:
            seguidas += 1
            inicio = inicio or x.hora
        elif NEGOCIACAO_OK in x.texto:
            seguidas, inicio = _fecha_sem_resposta(saida, seguidas, inicio)
    _fecha_sem_resposta(saida, seguidas, inicio)
    return saida


def _fecha_sem_resposta(saida, seguidas, inicio):
    if seguidas >= NEGOCIACOES_SEM_RESPOSTA:
        saida.append(f"{inicio}: Android Auto aceita e não responde ({seguidas} negociações seguidas sem resposta)")
    return 0, None


def _buracos(bloco):
    saida = []
    cinco = [x for x in bloco if x.texto.startswith("video fps target=")]
    for a, b in zip(cinco, cinco[1:]):
        # With the dash session over in between (it failed, the video stopped, a new stream began) nothing was due.
        entre = bloco[bloco.index(a):bloco.index(b)]
        if any(sinal in y.texto for y in entre for sinal in SEM_VIDEO):
            continue
        if b.segundos - a.segundos > BURACO_5S:
            saida.append(f"{b.hora}: buraco nas linhas de 5 s — {b.segundos - a.segundos:.1f} s desde a anterior")
    for x in bloco:
        m = re.search(r"gap max=([\d.]+)s", x.texto)
        if x.texto.startswith("resumo ") and m and float(m.group(1)) > 1.0:
            saida.append(f"{x.hora}: buraco de {float(m.group(1)):.1f} s sem quadro (resumo)")
        escuro = DARK.search(x.texto) if x.texto.startswith("resumo ") else None
        if escuro:
            saida += _sem_imagem_do_resumo(escuro, x, bloco)
    return saida


def _interrupcoes_da_imagem(bloco):
    """As interrupções da imagem pelo registro: [(parou, voltou)] de cada `video loop stop` ao `→ STREAMING` seguinte; `voltou` é None
    na última, se a projeção acabou sem a imagem voltar (o painel sumiu da varredura com a moto desligada, e o dono desligou)."""
    saida, parou = [], None
    for x in bloco:
        if "video loop stop" in x.texto and parou is None:
            parou = x
        elif "→ STREAMING" in x.texto and parou is not None:
            saida.append((parou, x))
            parou = None
    if parou is not None:
        saida.append((parou, None))
    return saida


def _sem_imagem_do_resumo(escuro, resumo, bloco):
    """O `dark=N/total (max maior)` do resumo, sem a interrupção final: a que começa num `video loop stop` e vai até o fim da projeção sem
    a imagem voltar é o painel desligado com a moto (ou o dono desligando), e o painel travado que dura até o fim já sai como "painel
    travado". Tira ela da contagem (e do maior, se ela era a maior: então o maior das outras vem do registro)."""
    n, maior = int(escuro.group(1)), tempo.duracao_s(escuro.group(3))
    total = escuro.group(2)
    # Only up to the resumo: the stop that comes with the rider's Desligar, after it, is not in its `dark=`.
    interrupcoes = _interrupcoes_da_imagem(bloco[:bloco.index(resumo)])
    final = next(((p, v) for p, v in interrupcoes if v is None), None)
    duracao = 0.0
    if final is not None:
        duracao = tempo.dif(resumo.segundos, final[0].segundos)
        n -= 1
        if n < 1:
            return []
        total = tempo.duracao_resumo(max(0.0, tempo.duracao_s(total) - duracao))
        if abs(maior - duracao) <= 5:
            fechadas = [tempo.dif(v.segundos, p.segundos) for p, v in interrupcoes if v is not None]
            maior = max(fechadas, default=0)
    if maior <= DARK_PROBLEMA_S:
        return []
    maior_texto = escuro.group(3) if final is None else tempo.duracao_resumo(maior)
    plural = "interrupção" if n == 1 else "interrupções"
    # The last one (the bike switched off, the image never back) is not counted, and the line says so with its size (item 17a: it was left out without a word).
    sem_contar = f"; sem contar a última, de {tempo.duracao_texto(duracao)}, com a moto desligada" if final is not None else ""
    # Every interruption that came back was an earlier switch-off of the bike (the dash vanished with a steady Wi-Fi and was back minutes later): said so, not a defect.
    moto = f" {MOTO_DESLIGADA}" if _todas_da_moto(bloco, [i for i in quedas.interrupcoes(bloco, _grupos_de_queda(bloco)) if not i["final"]]) else ""
    return [f"{resumo.hora}: painel sem imagem{moto} — {n} {plural}, {total} no total, a maior de {maior_texto} (resumo{sem_contar})"]


def _todas_da_moto(bloco, interrupcoes_):
    """Há interrupções e cada uma começou numa queda que é o painel desligando com a moto (`_painel_desligou`)."""
    if not interrupcoes_:
        return False
    da_moto = {id(g[0]) for g in _grupos_de_queda(bloco) if _painel_desligou(g, bloco)}
    return all(id(i["queda"]) in da_moto for i in interrupcoes_)


def _quedas(bloco):
    grupos = _grupos_de_queda(bloco)
    return [f"{b[0].hora}: quedas seguidas — {b[0].segundos - a[-1].segundos:.0f} s depois da anterior"
            for a, b in zip(grupos, grupos[1:]) if b[0].segundos - a[-1].segundos < QUEDAS_SEGUIDAS_S]


def _sessoes_ate_a_queda(bloco):
    """As sessões que chegaram a `→ STREAMING` e acabaram em `session fail`: [(linha do STREAMING, duração em s)]. Uma sessão que acaba por outro motivo (o piloto
    desligou, a projeção acabou) não entra, nem a falha de uma tentativa que nem chegou a transmitir (a espera de 5 min com a rede recusada)."""
    saida, transmitindo = [], None
    for x in bloco:
        if "→ STREAMING" in x.texto:
            transmitindo = x
        elif "session fail" in x.texto and x.nivel == "E" and transmitindo is not None:
            saida.append((transmitindo, x.segundos - transmitindo.segundos))
            transmitindo = None
    return saida


def _laco_de_reconexao(bloco):
    """Duas ou mais sessões seguidas que transmitiram e caíram em menos de `SESSAO_CURTA_S`: possível laço de reconexão (o painel sobe, fica `READY` e cai de novo, e o
    ritmo de espera volta a 2 s a cada `READY`). Em 50 sessões dos registros de 19/10 a 10/11/2026 não houve nenhum; este aviso é o gatilho para reabrir o orçamento de reconexão
    (estudo de 10/11/2026). Uma sessão curta isolada não avisa."""
    saida, corrida = [], []
    for sessao in _sessoes_ate_a_queda(bloco) + [(None, SESSAO_CURTA_S)]:
        if sessao[1] < SESSAO_CURTA_S:
            corrida.append(sessao)
            continue
        if len(corrida) >= SESSOES_CURTAS_LACO:
            detalhe = ", ".join(f"{x.hora} por {d:.0f} s" for x, d in corrida)
            saida.append(f"{corrida[0][0].hora}: possível laço de reconexão — {len(corrida)} sessões seguidas caíram em menos de {SESSAO_CURTA_S:.0f} s depois de transmitir ({detalhe})")
        corrida = []
    return saida


def _em_uso(texto):
    """Uma olhada do celular sem carregador e sem a tela apagada (o que não diz, vale): só nela o calor é do uso."""
    return _device(texto, "charging") != "yes" and _device(texto, "screen") != "off"


def _trechos_de_uso(bloco):
    """As temperaturas da bateria nas olhadas em uso (sem carregador, tela ligada), em trechos: o carregador (uma olhada com `charging=yes`,
    `charger in` ou `charger out`) fecha o trecho, e as olhadas até 10 min depois do último sinal dele são o calor da carga, não do uso."""
    trechos, atual, carga_em = [], [], None
    for x in bloco:
        carregador = (x.etiqueta == "system" and x.texto.startswith(("charger in", "charger out"))) or (
            x.texto.startswith("device ") and _device(x.texto, "charging") == "yes")
        if carregador:
            if atual:
                trechos.append(atual)
            atual, carga_em = [], x.segundos
        elif x.texto.startswith("device "):
            if carga_em is not None and 0 <= x.segundos - carga_em < CARGA_ESFRIA_S:
                continue
            g = _device(x.texto, "temp")
            if g and g != "?" and _em_uso(x.texto):
                atual.append(float(g))
    if atual:
        trechos.append(atual)
    return trechos


def _temperatura(bloco):
    olhadas = [x for x in bloco if x.texto.startswith("device ")]
    saida = []
    termicos = [_device(x.texto, "thermal") for x in olhadas]
    niveis = [TERMICO.index(t) for t in termicos if t in TERMICO]
    if niveis and max(niveis) >= TERMICO.index("moderate") and max(niveis) > niveis[0]:
        saida.append(f"temperatura subindo: estado térmico {TERMICO[niveis[0]]} → {TERMICO[max(niveis)]}")
    # Sem o carregador e com a tela ligada, e dentro de um trecho só (o carregador entrando, ou saindo, reinicia a conta): subida de 8 °C ou mais.
    for graus in _trechos_de_uso(bloco):
        sobe = max(g - min(graus[:i + 1]) for i, g in enumerate(graus))
        if sobe >= SOBE_GRAUS:
            saida.append(f"temperatura subindo: bateria de {min(graus):.1f} °C a {max(graus):.1f} °C (sem carregador, tela ligada)")
            break
    # Acima de 44 °C em qualquer olhada, com ou sem carregador: perto de onde o Android reduz a carga.
    quentes = [(x, float(g)) for x in olhadas for g in [_device(x.texto, "temp")] if g and g != "?" and float(g) > QUENTE_GRAUS]
    if quentes:
        x, g = max(quentes, key=lambda q: q[1])
        carregando = " (carregando)" if _device(x.texto, "charging") == "yes" else ""
        saida.append(f"temperatura subindo: bateria passou de {QUENTE_GRAUS:g} °C, {g:.1f} °C às {x.hora}{carregando}")
    return saida


# ── O que o registro conta e não é defeito (item 17a, 08/11/2026): vai nos AVISOS DA LEITURA e na linha do tempo ───────────────────────────────────────────────────
TELA_ACESA = ("screen on", "screen unlocked")
INATIVO = re.compile(r"Nav: cluster status INACTIVE")
MENU_DA_NAV_PELA_IMAGEM = re.compile(r"^aa-screen: menu \(by the picture: .*from nav\)")
TECLA_ENVIADA = re.compile(r"\bkey \d+ sent to Android Auto")
MENU_SEM_TECLA_S = 10.0  # a key of the REau this close before the menu is the rider's own
INATIVO_JUNTO_S = 10.0  # the `INACTIVE` lines of one episode (a recalculation sends two or three) come within this
VELOCIDADE = re.compile(r"speed~(\d+)km/h")
DOZE_VELOCIDADE_MIN_S = 60.0


def _tela_do_celular(x, bloco):
    """Se a tela do celular estava acesa na hora de `x`: pelo último evento `screen on/off/unlocked` ou olhada `screen=on|off` antes dele; None se o registro não diz."""
    for y in reversed(bloco[:bloco.index(x)]):
        if y.etiqueta == "system" and y.texto in TELA_EVENTOS:
            return y.texto in TELA_ACESA
        if y.texto.startswith("device "):
            tela = _device(y.texto, "screen")
            if tela in ("on", "off"):
                return tela == "on"
    return None


def _velocidades(bloco, de, ate):
    """As velocidades estimadas (`speed~N km/h` das linhas `video fps`) entre dois instantes do relógio do registro."""
    return [int(m.group(1)) for y in bloco if y.texto.startswith("video fps target=") and 0 <= tempo.dif(y.segundos, de) <= tempo.dif(ate, de)
            for m in [VELOCIDADE.search(y.texto)] if m]


def _painel_calou_primeiro(bloco):
    """As quedas que começaram pelo `RX watchdog` (o painel calou com o Wi-Fi ainda de pé: depois o Wi-Fi caiu, ou nem caiu): as outras quedas começam pelo Wi-Fi, e o
    `RX watchdog` vem 4 s depois como eco. O vigia não é problema em nenhuma (item 17a); o painel que calou com o Wi-Fi firme é só informação, com o sinal que o registro tem."""
    grupos = _grupos_de_queda(bloco)
    primeiras = [g for g in grupos if "RX watchdog" in g[0].texto]
    if not primeiras:
        return []
    detalhes = []
    for g in primeiras:
        painel = _wifi_do_painel(g, bloco)
        dbm = re.match(r"(-?\d+) dBm", painel)
        fraco = f", sinal fraco ({dbm.group(1)} dBm)" if dbm and int(dbm.group(1)) <= TRAVADO_SINAL_DBM else ""
        detalhes.append(f"{g[0].hora[:8]}{fraco}")
    return [f"projeção de {bloco[0].hora} ({bloco[0].arquivo}): {len(primeiras)} de {len(grupos)} quedas começaram pelo painel calado com o Wi-Fi ainda de pé "
            f"(o `RX watchdog` antes de o Wi-Fi cair; as outras começaram pelo Wi-Fi): {'; '.join(detalhes)}"]


def _menu_sem_tecla(bloco):
    """O menu que a imagem abriu (`aa-screen: menu (by the picture: …, from nav)`) sem nenhuma tecla do REau (tecla do painel, tecla enviada ao Android Auto, toque no celular)
    nos 10 s antes, com a tela do celular acesa (06/11 17:26:55 e 07/11 15:11:22.985): a conta de quantos `cluster status INACTIVE` (episódios) houve, com a tela acesa e
    apagada, e quantos viraram menu. Vazio se nenhum menu abriu assim com a tela acesa."""
    teclas = [y.segundos for y in bloco if (y.etiqueta == "dash" and TECLA_DO_PAINEL.match(y.texto) and TECLA_AVISO not in y.texto)
              or TOQUE_NO_CELULAR in y.texto or TECLA_ENVIADA.search(y.texto)]
    abertos = []
    for y in bloco:
        if y.etiqueta != "aa-screen" or not MENU_DA_NAV_PELA_IMAGEM.match(y.texto):
            continue
        if any(0 <= tempo.dif(y.segundos, t) <= MENU_SEM_TECLA_S for t in teclas):
            continue
        abertos.append((y, _tela_do_celular(y, bloco)))
    com_tela = [(y, t) for y, t in abertos if t]
    if not com_tela:
        return []
    episodios = []
    for y in bloco:
        if INATIVO.search(y.texto) and (not episodios or tempo.dif(y.segundos, episodios[-1].segundos) > INATIVO_JUNTO_S):
            episodios.append(y)
    ligados = sum(1 for y in episodios if _tela_do_celular(y, bloco))
    saida = []
    for y, _ in com_tela:
        inativo = next((e for e in reversed(episodios) if 0 <= tempo.dif(y.segundos, e.segundos) <= 10), None)
        depois = f", {tempo.dif(y.segundos, inativo.segundos):.1f} s depois de `cluster status INACTIVE`".replace(".", ",") if inativo else ""
        saida.append(f"{y.hora}: menu aberto sem tecla do REau com a tela do celular acesa{depois}: foi o Android Auto, porque a tela de navegação saiu "
                     "(provavelmente o dono mexendo no celular); o recorte errado dura até a imagem confirmar o menu")
    saida.append(f"projeção de {bloco[0].hora} ({bloco[0].arquivo}): de {len(episodios)} episódios de `cluster status INACTIVE`, {ligados} com a tela acesa e "
                 f"{len(episodios) - ligados} com a tela apagada ou sem dado; {len(com_tela)} {'virou' if len(com_tela) == 1 else 'viraram'} menu sem tecla, "
                 "todos com a tela acesa")
    return saida


def _cartoes_de_chamada(bloco):
    """O cartão de chamada do Android Auto: tocando (`AapCards: call card ringing` até `cleared`), sobre o mapa ou não, a velocidade estimada, se o REau mostrou o dele
    (`[dash] call card shown`), se o dono encerrou pelo joystick (`[call] end:`) e se foi com o painel fora; e a chamada em curso (`in a call`). Decidido em 08/11/2026: deixar
    como está; o leitor só conta."""
    interrupcoes = quedas.interrupcoes(bloco, _grupos_de_queda(bloco))
    saida = []
    for k, x in enumerate(bloco):
        if "AapCards: call card ringing" in x.texto:
            tipo, fim_texto = "tocando", "AapCards: call card cleared"
        elif "AapCards: call card in a call" in x.texto and not any("AapCards: call card in a call" in y.texto for y in bloco[:k][-3:]):
            tipo, fim_texto = "em curso", "AapCards: call card cleared"
        else:
            continue
        fim = next((y for y in bloco[k + 1:] if fim_texto in y.texto), None) or bloco[-1]
        dur = tempo.dif(fim.segundos, x.segundos)
        tela = next((TELA_NOME[m.group(1)] for y in reversed(bloco[:k]) if y.etiqueta == "aa-screen" for m in [TELA_AA.match(y.texto)] if m), None)
        partes = [f"{tipo} {tempo.dec(dur)} s ({x.hora[:8]} → {fim.hora[:8]})", f"tela {tela or 'sem dado'}"]
        vel = _velocidades(bloco, x.segundos, fim.segundos) or _velocidades(bloco, x.segundos - 15, x.segundos)
        if vel:
            partes.append(f"velocidade estimada {min(vel)} a {max(vel)} km/h")
        meio = bloco[k:bloco.index(fim) + 1]
        if tipo == "tocando":
            partes.append("cartão do REau mostrado no painel" if any("[dash]" in y.texto or "call card shown" in y.texto for y in meio) else "sem o cartão do REau no painel")
        fim_joystick = next((y for y in meio if y.etiqueta == "call" and y.texto.startswith("end: ")), None)
        if fim_joystick is not None:
            partes.append(f"encerrada pelo joystick {tempo.dec(tempo.dif(fim_joystick.segundos, x.segundos))} s depois")
        fora = next((i for i in interrupcoes if tempo.dif(x.segundos, i["perda"]) <= i["total"]), None)
        if fora is not None:
            partes.append("com o painel fora (sem imagem)")
        saida.append(f"{x.hora[:8]}: cartão de chamada do Android Auto {'; '.join(partes)}"
                     + (" — cobre cerca de 45 % de cima do recorte de navegação (decidido em 08/11/2026: deixar como está)" if tipo == "tocando" and tela == "mapa" else ""))
    return saida


def _doze(bloco):
    """`doze on` até `doze off` com a projeção ligada: quanto durou e a velocidade estimada nesse tempo (o doze profundo exige o aparelho parado: com 128 a 140 km/h é doze leve, ou a
    velocidade estimada não vale)."""
    saida = []
    for k, x in enumerate(bloco):
        if x.etiqueta != "system" or x.texto != "doze on":
            continue
        fim = next((y for y in bloco[k + 1:] if y.etiqueta == "system" and y.texto == "doze off"), None) or bloco[-1]
        vel = _velocidades(bloco, x.segundos, fim.segundos)
        v = f"; velocidade estimada de {min(vel)} a {max(vel)} km/h nesse tempo (o doze profundo exige o aparelho parado: pode ser doze leve)" if vel else ""
        saida.append(f"{x.hora[:8]}: o celular entrou em doze com a projeção ligada, até {fim.hora[:8]} ({tempo.duracao_texto(tempo.dif(fim.segundos, x.segundos))}){v}")
    return saida


def _economia(bloco):
    """A economia de bateria: a que ligou sozinha (`battery low at N%` e `saver on` juntos: o nível automático do Android) e a que já estava ligada na partida (`saver=on` na primeira olhada)."""
    saida = []
    primeira = next((y for y in bloco if y.texto.startswith("device ")), None)
    ligada_na_partida = primeira is not None and _device(primeira.texto, "saver") == "on"
    for k, x in enumerate(bloco):
        if x.etiqueta == "system" and x.texto.startswith("saver on"):
            if "came late" in x.texto and ligada_na_partida:
                continue  # the line of the state it started in, said apart below
            baixa = next((y for y in reversed(bloco[:k]) if tempo.dif(x.segundos, y.segundos) <= 5 and y.texto.startswith("battery low at")), None)
            nivel = re.match(r"battery low at (\d+)%", baixa.texto) if baixa else None
            if nivel:
                saida.append(f"{x.hora[:8]}: a economia de bateria ligou sozinha, no nível automático do Android ({nivel.group(1)} %)")
            else:
                saida.append(f"{x.hora[:8]}: a economia de bateria ligou" + (" (a linha chegou tarde)" if "came late" in x.texto else ""))
    if ligada_na_partida:
        saida.append(f"projeção de {bloco[0].hora} ({bloco[0].arquivo}): a economia de bateria já estava ligada na partida (saver=on)")
    return saida


def _informes(bloco):
    """Tudo o que o registro de uma projeção conta e não é defeito: o painel que calou primeiro, o menu sem tecla, os cartões de chamada, o doze e a economia de bateria."""
    return _painel_calou_primeiro(bloco) + _menu_sem_tecla(bloco) + _cartoes_de_chamada(bloco) + _doze(bloco) + _economia(bloco)


def _eventos_do_painel(t):
    """As linhas do ramo do painel na linha do tempo: a escrita das unidades do painel (`panel settings: …`), a unidade das distâncias da guiagem (`nav distances in …`) e o
    aviso do painel de que mudou um ajuste (`dash says it changed a setting`). Vazio para o resto (no REau principal essas linhas só não existem)."""
    if t.startswith(PAINEL_ESCRITA):
        m = PAINEL_RESULTADO.match(t)
        if t.startswith(PAINEL_ESCRITA + "writing "):
            mudanca = PAINEL_MUDANCAS.search(t)
            return [f"escrita no painel pedida pelo dono: {mudanca.group(1) if mudanca else t[len(PAINEL_ESCRITA):80]}"]
        if t.startswith(PAINEL_ENVIADO):
            return ["escrita no painel: o bloco foi ao fio uma vez; pediu os dados de novo (0A 02) e espera a confirmação do painel"]
        if m and m.group(1) == "CONFIRMED":
            return [f"escrita no painel: CONFIRMADA em {m.group(2)} s ({m.group(3)})"]
        if m:
            return [f"escrita no painel: NÃO confirmada em {m.group(2)} s ({m.group(3)})"]
        if t.startswith(PAINEL_RECUSADO):
            return ["escrita no painel: o soquete recusou o bloco, NADA foi enviado ao painel (SEND_FAILED)"]
        if t.startswith(PAINEL_ESCRITA + "the ask for the data (0A 02) did not go to the wire"):
            return ["escrita no painel: o pedido dos dados de novo (0A 02) não foi ao fio; a espera segue (o painel pode confirmar sozinho)"]
        if "the wait was cancelled" in t:
            return ["escrita no painel: a espera foi cancelada (o bloco já tinha ido)"]
        if t.startswith(PAINEL_ESCRITA + "not written"):
            return [f"escrita no painel: não escrita {t[len(PAINEL_ESCRITA + 'not written'):].strip()}"]
        return [f"escrita no painel: {t[len(PAINEL_ESCRITA):100]}"]
    m = PAINEL_NAV_UNIDADE.match(t)
    if m:
        unidade = "pés e décimos de milha" if "feet" in m.group(1) else "metros e décimos de quilômetro"
        return [f"guiagem: distâncias em {unidade} ({m.group(2)})"]
    m = PAINEL_AVISO_09_05.match(t)
    if m:
        if m.group(1):
            return [f"o painel disse 09 05 = {m.group(1)} (não é o 01 de ajuste mudado): nada feito"]
        pede = "pede os dados de novo (0A 02)" if "asking for its data again" in t else "não pede de novo (já pediu há pouco ou 5 vezes)"
        return [f"o painel avisou que mudou um ajuste (09 05 = 01): o app não manda nada de volta e {pede}"]
    return []


def _envio_por_politica(bloco):
    """O envio ao painel recusado pela política de threads do Android (`NetworkOnMainThreadException`: o app mandou o pacote da thread principal): defeito do app, não queda do
    Wi-Fi. O pacote NÃO saiu, e o texto «link down?» da linha engana (o Wi-Fi estava de pé). Uma linha por tipo de envio (controle `TX` ou vídeo `RTP`) e por projeção."""
    achadas = {}
    for x in bloco:
        m = ENVIO_POR_POLITICA.search(x.texto)
        if m:
            achadas.setdefault(m.group(1), []).append(x)
    saida = []
    for tipo, xs in achadas.items():
        quando = f"{xs[0].hora}" + (f" a {xs[-1].hora}" if len(xs) > 1 else "")
        saida.append(f"{quando}: defeito do app, não queda do Wi-Fi: o envio {tipo} ao painel foi recusado pela política de threads do Android (NetworkOnMainThreadException: "
                     f"o app mandou o pacote da thread principal) e o pacote NÃO saiu; {len(xs)} {'vez' if len(xs) == 1 else 'vezes'} nesta projeção (o «link down?» da linha engana)")
    return saida


def _perto(pacotes, x):
    """Algum pacote de [(arquivo, segundos)] no mesmo registro de `x`, a `PAINEL_TX_JANELA_S` s antes ou depois dela."""
    return any(a == x.arquivo and abs(s - x.segundos) <= PAINEL_TX_JANELA_S for a, s in pacotes)


def _escritas_no_painel(bloco, tx_do_painel):
    """O que a escrita das unidades do painel (o ramo) tem de errado: o soquete que recusou o bloco (nada saiu), a que o painel não confirmou e a que o app diz ter enviado
    ("sent once") sem nenhum pacote `04xx` nem `0A02` no registro de pacotes (`[detail]`, ligado nesta projeção) logo depois: o que a corrida de 08/11/2026 mostrou
    (`NetworkOnMainThreadException`). `tx_do_painel`: [(arquivo, segundos)] dos pacotes `04xx`/`0A02` que o registro de pacotes traz, e (arquivo, segundos) de qualquer
    `dash tx`, para saber se o registro de pacotes estava ligado."""
    saida, pedido = [], None
    qualquer, so_do_painel = tx_do_painel
    for x in bloco:
        t = x.texto
        if not t.startswith(PAINEL_ESCRITA):
            continue
        if t.startswith(PAINEL_ESCRITA + "writing "):
            m = PAINEL_MUDANCAS.search(t)
            pedido = m.group(1) if m else "?"
        elif t.startswith(PAINEL_RECUSADO):
            saida.append(f"{x.hora}: escrita no painel NÃO enviada ({pedido}): o soquete recusou o bloco (SEND_FAILED), nada saiu")
        elif t.startswith(PAINEL_ENVIADO):
            if _perto(qualquer, x) and not _perto(so_do_painel, x):
                saida.append(f"{x.hora}: escrita no painel ({pedido}): o app diz «sent once», mas o registro de pacotes (`[detail]`, ligado nesse trecho) não tem nenhum pacote 04xx nem 0A02 "
                             f"a {PAINEL_TX_JANELA_S:g} s dessa hora: nada foi ao fio")
        else:
            m = PAINEL_RESULTADO.match(t)
            if m and m.group(1) == "NOT confirmed":
                saida.append(f"{x.hora}: escrita no painel NÃO confirmada ({pedido}) em {m.group(2)} s: {m.group(3)}")
    return saida


def da_escrita_no_painel(caminhos, linhas):
    """Os achados da escrita das unidades do painel (`_escritas_no_painel`) de todas as projeções; lê o registro de pacotes só se alguma escrita disse «sent once»."""
    blocos = projecoes(linhas)
    tx = _tx_do_painel(caminhos) if any(x.texto.startswith(PAINEL_ENVIADO) for b in blocos for x in b) else ([], [])
    return [a for b in blocos for a in _escritas_no_painel(b, tx)]


def _tx_do_painel(caminhos):
    """([(arquivo, segundos)] de todo `dash tx` do registro de pacotes, [(arquivo, segundos)] dos que levam um grupo 04 ou o 0A02): as linhas `[detail]` que `ler` não guarda."""
    qualquer, do_painel = [], []
    arquivos, _ = _registros_de(caminhos)
    for a in arquivos:
        for bruta in a.read_text(encoding="utf-8", errors="replace").splitlines():
            if DETALHE not in bruta or " dash tx" not in bruta:
                continue
            m = LINHA.match(bruta)
            if not m:
                continue
            h, mi, se = m.group(1).split(":")
            segundos = int(h) * 3600 + int(mi) * 60 + float(se)
            qualquer.append((a.name, segundos))
            if PAINEL_TX.search(bruta):
                do_painel.append((a.name, segundos))
    return qualquer, do_painel


LEVA5_SILENCIO = re.compile(r"RX quiet: no dash packets for ([\d.]+) s")
LEVA5_VOLTA = re.compile(r"RX back: the dash spoke again after ([\d.]+) s of silence")
LEVA5_VELOCIDADE = re.compile(r"speed profile: reads the GPS speed \(([\d.]+) km/h; the route says ([^)]+)\)")
LEVA5_COR = re.compile(r"encoder colour: asked ([^;]+); the codec says (.+)")
LEVA5_COR_RECUSADA = re.compile(r"encoder (.+) refused the colour keys \(([^)]+)\): (.+); asked again without them")
LEVA5_PROTOCOLO = re.compile(r"Handshake: asked protocol (\d+\.\d+), answered (.+?): the handshake (completed|did not complete)")
LEVA5_RECALCULO = re.compile(r"route: a short inactive shown as a recalculation; holding it up to ([\d.]+) s")
LEVA5_RECALCULO_FIM = re.compile(r"route: the guidance is back after ([\d.]+) s of inactive")
LEVA5_RECALCULO_EXPIROU = re.compile(r"route: the inactive did not end in ([\d.]+) s")


def _eventos_da_leva5(etiqueta, t):
    """As linhas das chaves experimentais da leva 5 (11/11/2026) na linha do tempo, em português: a vigia tolerante do painel (`RX quiet`/`RX back`), a velocidade do GPS no perfil, as
    cores do codificador, o protocolo 6.0 do Android Auto e o «inativo» curto da rota. Vazio para o resto. (O teto pela bateria é a etiqueta `heat`: `_aviso_calor`.)"""
    if etiqueta == "dash":
        m = LEVA5_SILENCIO.match(t)
        if m:
            return [f"painel calado há {m.group(1)} s: só registrado, a sessão segue (vigia tolerante, chave experimental)"]
        m = LEVA5_VOLTA.match(t)
        if m:
            return [f"painel voltou depois de {m.group(1)} s calado: quadro-chave pedido (vigia tolerante, chave experimental)"]
        m = LEVA5_RECALCULO.match(t)
        if m:
            return [f"rota: um «inativo» curto do Android Auto virou recalculando por até {m.group(1)} s (chave experimental)"]
        m = LEVA5_RECALCULO_FIM.match(t)
        if m:
            return [f"rota: a guiagem voltou depois de {m.group(1)} s de «inativo»: era um recalculo (chave experimental)"]
        m = LEVA5_RECALCULO_EXPIROU.match(t)
        if m:
            return [f"rota: o «inativo» não acabou em {m.group(1)} s: o cartão da rota foi apagado (chave experimental)"]
    if etiqueta == "stream":
        m = LEVA5_VELOCIDADE.match(t)
        if m:
            return [f"perfil de velocidade passou a ler o GPS: {m.group(1)} km/h (a rota diz {m.group(2)}); chave experimental"]
        if t.startswith("speed profile: back to the route"):
            return ["perfil de velocidade voltou à estimativa da rota (sem velocidade do GPS ou sem rota)"]
        m = LEVA5_COR.match(t)
        if m:
            return [f"cores do codificador pedidas ({m.group(1)}); o codec diz: {m.group(2)}"]
        m = LEVA5_COR_RECUSADA.search(t)
        if m:
            return [f"o codificador recusou as cores pedidas ({m.group(2)}): {m.group(3)}; pedido de novo sem elas"]
    if etiqueta == "aa":
        m = LEVA5_PROTOCOLO.search(t)
        if m:
            fim = "o aperto de mão completou" if m.group(3) == "completed" else "o aperto de mão NÃO completou: o 1.7 volta a ser pedido até o app fechar"
            return [f"protocolo {m.group(1)} pedido ao Android Auto, respondido «{m.group(2)}»: {fim} (chave experimental)"]
    return []


def _eventos_novos(x, bloco):
    """As linhas de linha do tempo para o que o leitor não contava antes: `has not joined`, as chamadas (`[call]`), o doze e a economia de bateria."""
    t = x.texto
    if "has not joined" in t:
        return ["o painel estava na varredura quando o pedido saiu e não foi unido: o app pede de novo (" + t.split(" the dash was ", 1)[-1][:90] + ")"]
    if x.etiqueta == "call":
        if "mode=RINGTONE" in t:
            return ["chamada tocando (modo de áudio RINGTONE)"]
        if "mode=IN_COMMUNICATION" in t and not any(y.etiqueta == "call" and "mode=IN_COMMUNICATION" in y.texto for y in bloco[:bloco.index(x)][-4:]):
            return ["chamada em curso (modo de áudio IN_COMMUNICATION)"]
        if t.startswith("end: "):
            return [f"chamada encerrada pelo joystick ({t[5:60]})"]
        return []
    if x.etiqueta == "dash":
        return _eventos_do_painel(t) or _eventos_da_leva5(x.etiqueta, t)
    if x.etiqueta in ("stream", "aa"):
        return _eventos_da_leva5(x.etiqueta, t)
    if x.etiqueta == "system" and t in ("doze on", "doze off"):
        return [f"celular em doze: {'ligou' if t == 'doze on' else 'saiu'}"]
    if x.etiqueta == "system" and t.startswith("saver on"):
        return ["economia de bateria ligou"]
    return []


# O `logcat-crash.txt` da coleta: o processo que caiu com `Fatal signal 11 (SIGSEGV)`; o do app é defeito, o dos outros é do Android.
CRASH_SINAL = re.compile(r"^(\d\d)-(\d\d) (\d\d:\d\d:\d\d)\.\d+\s+\d+\s+\d+ F libc\s+: Fatal signal (\d+) \((\w+)\).* pid \d+ \(([^)]+)\)")


def _crash_da_coleta(caminhos, linhas):
    """[(achado, e_do_app)] das linhas `Fatal signal N (SIG…)` do `logcat-crash.txt` da coleta, só dos dias dos registros lidos (o `SIGSEGV` do `audioserver` de 07/11 17:49:23 é
    do Android, fora do REau: informativo; um processo do REau é defeito)."""
    dias = {(int(x.dia[5:7]), int(x.dia[8:10])) for x in linhas if x.dia}
    saida = []
    for c in map(Path, caminhos):
        raiz = c if c.is_dir() else c.parent
        for pasta in (raiz, raiz.parent if raiz.name == "diag" else raiz):
            arquivo = pasta / "logcat-crash.txt"
            if not arquivo.is_file():
                continue
            for bruta in arquivo.read_text(encoding="utf-8", errors="replace").splitlines():
                m = CRASH_SINAL.match(bruta)
                if m and (not dias or (int(m.group(1)), int(m.group(2))) in dias):
                    do_app = "com.reau" in m.group(6)
                    saida.append((f"{m.group(2)}/{m.group(1)} {m.group(3)}: {m.group(6)} caiu com {m.group(5)} (sinal {m.group(4)})"
                                  + ("" if do_app else ": é do Android, fora do REau (informativo)"), do_app))
            break
    return list(dict.fromkeys(saida))


def _por_data_e_build(linhas):
    """Uma linha por dia e build do app: quantas projeções ligaram, quantas quedas e quanto transmitiu (o resumo de cada projeção); só o que o registro tem."""
    versoes, atual, por = _versao_de_cada_projecao(linhas), None, {}
    for bloco in projecoes(linhas):
        dia = bloco[0].dia or "sem data"
        build = versoes.get(id(bloco[0])) or "sem versão"
        n = por.setdefault((dia, build), {"projecoes": 0, "quedas": 0, "segundos": 0.0})
        n["projecoes"] += 1
        n["quedas"] += len(_grupos_de_queda(bloco))
        n["segundos"] += tempo.dif(bloco[-1].segundos, bloco[0].segundos)
    return [f"{dia[8:10] + '/' + dia[5:7] if len(dia) == 10 else dia}: build {build}: {n['projecoes']} {'projeção' if n['projecoes'] == 1 else 'projeções'}, "
            f"{tempo.duracao_texto(n['segundos'])} ligada{'s' if n['projecoes'] != 1 else ''}, {n['quedas']} {'queda' if n['quedas'] == 1 else 'quedas'}" for (dia, build), n in sorted(por.items())]


def _sinal_antes_dbm(grupo, bloco):
    """O sinal (dBm) do Wi-Fi do painel antes de uma queda: o último `bike=` das olhadas `[device]` até `BIKE_ATE_S` s antes, ou o pior das últimas `AMOSTRAS_RSSI` amostras do
    histórico `[link]`; None sem nenhum dos dois."""
    queda = grupo[0]
    for x in reversed(bloco[:bloco.index(queda)]):
        m = BIKE_DEVICE.search(x.texto) if x.texto.startswith("device ") else None
        if m and queda.segundos - x.segundos <= BIKE_ATE_S:
            return int(m.group(1))
    antes = _wifi_antes(grupo, bloco)
    historico = HISTORICO_RSSI.search(antes[-1].texto) if antes else None
    amostras = [int(v) for v in historico.group(1).split() if re.fullmatch(r"-?\d+", v)][-AMOSTRAS_RSSI:] if historico else []
    return min(amostras) if amostras else None


def corridas(linhas):
    """Uma entrada por projeção para comparar as chaves experimentais (`chaves.py`): o conjunto de chaves ligadas, as horas transmitindo, as quedas (sem as do painel que desligou
    com a moto), as com Wi-Fi firme (sinal de `chaves.FIRME_DBM` ou melhor antes da queda; sem sinal no registro não é firme), `late`, kbps e o tempo sem imagem."""
    blocos = projecoes(linhas)
    saida = []
    for bloco, (ligadas, conhecido) in zip(blocos, chaves.por_projecao(linhas, blocos)):
        grupos = [g for g in _grupos_de_queda(bloco) if not _painel_desligou(g, bloco)]
        firmes = sum(1 for g in grupos if (_sinal_antes_dbm(g, bloco) or -999) >= chaves.FIRME_DBM)
        saida.append(chaves.fatos(bloco, ligadas, conhecido, len(grupos), firmes))
    return saida


def _instante_da_opcao(texto, fim_do_dia=False):
    """`2026-11-05`, `2026-11-05T07:00` ou `2026-11-05 07:00` como datetime; um dia sem hora vale o começo dele (ou o fim, em `--ate`). None se não for uma data."""
    for formato in ("%Y-%m-%dT%H:%M", "%Y-%m-%d %H:%M", "%Y-%m-%d"):
        try:
            quando = datetime.strptime(texto, formato)
        except ValueError:
            continue
        return quando + timedelta(days=1) - timedelta(microseconds=1) if fim_do_dia and formato == "%Y-%m-%d" else quando
    return None


def _opcoes(argv):
    """(caminhos, desde, ate, tudo) da linha de comando, ou (mensagem de erro, None, None, None). `--desde` e `--ate` aceitam `AAAA-MM-DD` ou `AAAA-MM-DDTHH:MM`; `--tudo` lê todos os
    registros (sem o corte da coleta anterior)."""
    caminhos, desde, ate, tudo, args = [], None, None, False, list(argv)
    while args:
        a = args.pop(0)
        if a in ("--desde", "--ate"):
            valor = args.pop(0) if args else ""
            quando = _instante_da_opcao(valor, fim_do_dia=(a == "--ate"))
            if quando is None:
                return f"ler_registro.py: {a} pede uma data AAAA-MM-DD ou AAAA-MM-DDTHH:MM, não {valor!r}", None, None, None
            desde, ate = (quando, ate) if a == "--desde" else (desde, quando)
        elif a == "--tudo":
            tudo = True
        elif a.startswith("-"):
            return f"ler_registro.py: argumento desconhecido {a!r} (use --help)", None, None, None
        elif not Path(a).exists():
            return f"ler_registro.py: {a!r} não existe", None, None, None
        else:
            caminhos.append(a)
    return caminhos, desde, ate, tudo


def main(argv):
    if any(a in ("-h", "--help") for a in argv):
        print(__doc__)
        return 0
    if not argv:
        print(__doc__)
        return 2
    caminhos, desde, ate, tudo = _opcoes(argv)
    if isinstance(caminhos, str):
        print(caminhos, file=sys.stderr)
        return 2
    if not caminhos:
        print(__doc__)
        return 2
    info = {}
    linhas = ler(caminhos, desde, ate, corte_padrao=not tudo and desde is None, info=info)
    for pasta, quando in info["cortes"]:
        print(f"recorte: só os registros que acabam depois da coleta anterior ({pasta.name}, {quando:%d/%m %H:%M}); `--tudo` lê todos, `--desde`/`--ate` escolhem a janela")
    if desde is not None or ate is not None:
        print(f"recorte: janela de {desde:%d/%m %H:%M} " if desde else "recorte: até ", end="")
        print(f"até {ate:%d/%m %H:%M}" if desde and ate else (f"{ate:%d/%m %H:%M}" if ate else "em diante"))
    if info["excluidos"]:
        print(f"recorte: {len(info['excluidos'])} registros fora da janela ({info['excluidos'][0]} a {info['excluidos'][-1]})")
    for v in versoes_do_app(linhas):
        print(v)
    for g in _por_data_e_build(linhas):
        print(f"por data e build — {g}")
    print("\n".join(linha_do_tempo(linhas)) or "nenhuma projeção nos arquivos")
    janela = (desde, ate) if desde is not None or ate is not None else None
    achados = problemas(linhas) + do_sistema(caminhos, linhas, janela) + do_dumpsys_wifi(caminhos, linhas, janela) + da_escrita_no_painel(caminhos, linhas)
    achados += [a for a, do_app in _crash_da_coleta(caminhos, linhas) if do_app]
    moto = [a for a in achados if MOTO_DESLIGADA in a]
    achados = [a for a in achados if MOTO_DESLIGADA not in a]
    print("\nPROBLEMAS:" if achados else "\nnenhum problema conhecido")
    for p in achados:
        print(f"  - {p}")
    if moto:
        print("\nMOTO DESLIGADA (o painel sumiu junto com a moto: não é defeito do app, não conta no código de saída):")
        for m in moto:
            print(f"  - {m}")
    teste = varredura.texto_do_teste(varredura.teste_1a(linhas))
    if teste:
        print("\nTESTE DO ITEM 1a (busca de redes com o app atrás):")
        for t in teste:
            print(f"  {t}")
    todas = corridas(linhas)
    if todas and any(c["conhecido"] for c in todas):
        print("\nCORRIDAS POR CHAVES EXPERIMENTAIS (critérios na ajuda e em chaves.py):")
        for t in chaves.texto(todas):
            print(f"  {t}")
    elif todas:
        print(f"\nCORRIDAS POR CHAVES EXPERIMENTAIS: nenhuma das {len(todas)} projeções traz o estado das chaves (build anterior às chaves experimentais): nada a comparar")
    pacotes = detalhes.secao(_registros_de(caminhos)[0], projecoes(linhas))
    if pacotes:
        print("\nPACOTES DO PAINEL (registro `[detail]`: o batimento e o grupo 03 de cada conexão):")
        for t in pacotes:
            print(f"  {t}")
    for raiz in gravacoes.raizes_da_coleta(caminhos):
        gravadas = gravacoes.secao(raiz, linhas, projecoes(linhas))
        if gravadas:
            print(f"\nGRAVAÇÕES DA COLETA ({raiz.name}: sessions/ e video/):")
            for t in gravadas:
                print(f"  {t}")
    leituras = olhadas.secao(projecoes(linhas))
    if leituras:
        print("\nLEITURAS DO CLASSIFICADOR DE IMAGEM (picture look com p < 0,98; o alarme está em PROBLEMAS):")
        for t in leituras:
            print(f"  {t}")
    avisos = avisos_da_leitura(caminhos, linhas, janela)
    if avisos:
        print("\nAVISOS DA LEITURA (não são defeitos do app):")
        for a in avisos:
            print(f"  - {a}")
    return 1 if achados else 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.exit(main(sys.argv[1:]))
