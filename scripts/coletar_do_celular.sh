#!/usr/bin/env bash
# Coleta, por adb e SÓ PARA LER, o que ajuda a investigar um problema do REau num celular: o estado do celular e do Android Auto,
# o logcat (o do sistema e o buffer de quedas) e as pastas de registro do app. Nunca instala, apaga nem muda configuração:
# os únicos comandos são de leitura (getprop, dumpsys, cmd appops get, am get-standby-bucket, settings get, logcat -d, logcat -g) e a
# cópia das pastas do app por run-as (só na build debug).
#
# Uso:  scripts/coletar_do_celular.sh [SERIAL [PASTA_DE_SAIDA]]
#   SERIAL   o aparelho (adb devices); sem ele, só roda se houver exatamente UM aparelho conectado.
#   PASTA    onde guardar (padrão: uma pasta celular-AAAAMMDD-HHMM numa pasta de registros ao lado da pasta do repositório, fora dele).
# Variável ADB: o executável do adb (padrão: adb). Variável REAU_SAIDA: a pasta de saída quando não se dá o serial.
#
# ATENÇÃO: o logcat do sistema tem dado pessoal (o nome do dono, e-mails, o nome inteiro das redes Wi-Fi, a lista de apps).
# Não compartilhe a pasta sem filtrar (só os buffers crash e as linhas do gearhead e do com.reau.app).
set -euo pipefail

ADB="${ADB:-adb}"
SERIAL="${1:-}"

devices() { "$ADB" devices | awk 'NR>1 && $2=="device" {print $1}'; }

if [ -z "$SERIAL" ]; then
  found="$(devices)"
  count="$(printf '%s\n' "$found" | grep -c . || true)"
  if [ "$count" -eq 0 ]; then echo "nenhum aparelho conectado e autorizado (adb devices)" >&2; exit 2; fi
  if [ "$count" -gt 1 ]; then
    echo "mais de um aparelho conectado; diga qual: $0 SERIAL" >&2
    printf '%s\n' "$found" >&2
    exit 2
  fi
  SERIAL="$found"
elif ! devices | grep -qx -- "$SERIAL"; then
  echo "o aparelho $SERIAL não está conectado e autorizado (adb devices)" >&2
  exit 2
fi

OUT="${2:-${REAU_SAIDA:-$(cd "$(dirname "$0")/../.." && pwd)/reau_registros/celular-$(date +%Y%m%d-%H%M)}}"
mkdir -p "$OUT"
no_celular() { "$ADB" -s "$SERIAL" shell "$@"; }
GEARHEAD=com.google.android.projection.gearhead

{
  echo "# coletado por adb (somente leitura) em $(date); aparelho $SERIAL"
  echo "modelo: $(no_celular getprop ro.product.model | tr -d '\r')  android: $(no_celular getprop ro.build.version.release | tr -d '\r')"
  echo "rom: $(no_celular getprop ro.build.display.id | tr -d '\r')  lineage: $(no_celular getprop ro.lineage.version | tr -d '\r')"
  for pkg in com.reau.app com.reau.app.lite "$GEARHEAD"; do
    echo "--- $pkg"
    no_celular dumpsys package "$pkg" | grep -m4 -E "versionName|versionCode|lastUpdateTime|installerPackageName" || true
  done
  echo "--- o Android Auto em segundo plano"
  no_celular cmd appops get "$GEARHEAD" RUN_ANY_IN_BACKGROUND || true
  no_celular am get-standby-bucket "$GEARHEAD" || true
  no_celular dumpsys deviceidle whitelist | grep -E "gearhead|reau" || echo "(nenhum dos dois na lista de isenção do Doze)"
  echo "--- economia de bateria e bateria"
  echo "low_power=$(no_celular settings get global low_power | tr -d '\r')"
  no_celular dumpsys power | grep -iE "saver|low_power" | head -8 || true
  no_celular dumpsys battery | head -14 || true
} > "$OUT/INFO.txt" 2>&1

# Os buffers do sistema: tudo o que o aparelho ainda guarda, e o buffer das quedas.
"$ADB" -s "$SERIAL" logcat -b all -d -v threadtime > "$OUT/logcat-sistema.txt" 2>&1 || true
"$ADB" -s "$SERIAL" logcat -b crash -d -v threadtime > "$OUT/logcat-crash.txt" 2>&1 || true
# O que o SISTEMA diz do Wi-Fi do painel na hora de uma queda (wpa_supplicant, ClientModeImpl, ConnectivityService...): um app comum só lê o
# próprio logcat (sem READ_LOGS), então isto só se pega por adb (plano 25). Fica em sistema/: o estado do Wi-Fi e da conectividade
# (dumpsys wifi: o histórico de eventos do cliente Wi-Fi, com os motivos de queda; dumpsys connectivity: as redes, os pedidos e os eventos recentes),
# o tamanho dos buffers do logcat (quanta história o aparelho guarda: menos de 1 h costuma ser tudo) e as linhas de Wi-Fi/rede extraídas do
# logcat de todos os buffers. Rode logo depois da corrida: o buffer é curto. Só leitura; nenhum filtro de privacidade além da cópia mascarada.
mkdir -p "$OUT/sistema"
no_celular dumpsys wifi > "$OUT/sistema/dumpsys-wifi.txt" 2>&1 || true
no_celular dumpsys connectivity > "$OUT/sistema/dumpsys-connectivity.txt" 2>&1 || true
"$ADB" -s "$SERIAL" logcat -g > "$OUT/sistema/logcat-tamanho-dos-buffers.txt" 2>&1 || true
# O que o Android diz do acesso do REau à localização (a busca de redes com o app atrás depende dela; item 1a, pedido em 08/11/2026): os
# modos de cada permissão do app (`cmd appops get`, com o `rejectTime` de uma recusa) e as linhas do `dumpsys location` que falam do REau
# (pedidos e usos de localização). Só leitura; o filtro deixa de fora o que é de outros apps.
no_celular cmd appops get com.reau.app > "$OUT/sistema/appops-reau.txt" 2>&1 || true
no_celular dumpsys location 2>&1 | grep -i "com\.reau\.app" > "$OUT/sistema/location-reau.txt" || true
grep -E "wpa_supplicant|WifiService|WifiClientModeImpl|ClientModeImpl|WifiConnectivityManager|WifiNetworkFactory|WifiNetworkSelector|WifiNative|WifiHealthMonitor|WifiScanner|ConnectivityService|NetworkMonitor|NetworkAgent|netd|RE_[A-Za-z0-9]" \
  "$OUT/logcat-sistema.txt" > "$OUT/sistema/wifi-linhas.txt" 2>/dev/null || true

# Uma cópia para compartilhar, sem o nome Wi-Fi do painel (RE_xxxx_aammdd, que prende o registro a uma moto e a um dono); o original fica.
# O resto do dado pessoal do logcat (nome, e-mails, outras redes, apps) continua nele: filtre antes de compartilhar.
if command -v python3 >/dev/null 2>&1; then
  python3 -I "$(dirname "$0")/registro/mascarar.py" "$OUT/logcat-sistema.txt" "$OUT/logcat-sistema.mascarado.txt" || true
  for arquivo in dumpsys-wifi dumpsys-connectivity wifi-linhas appops-reau location-reau; do
    python3 -I "$(dirname "$0")/registro/mascarar.py" "$OUT/sistema/$arquivo.txt" "$OUT/sistema/$arquivo.mascarado.txt" || true
  done
fi

# As pastas de registro do app (só a build debug deixa o run-as ler): com.reau.app (o app único desde a 0.3.0, e a antiga Completa) e, se
# ainda existir, a antiga Lite (com.reau.app.lite, só até 0.2.0); a da antiga Lite vai para a subpasta antigo-lite/ para não se misturar.
# sessions/ vem inteira: além do anel da gravação (reau_<data>_<hora>_<ms>[.1].aasession), o que a Marca do piloto congela
# (reau_<data>_<hora>_marca[.1].aasession, que o anel não sobrescreve; plano 22). Nenhum filtro por nome.
# Uma pasta por vez: a que não existe (ainda sem gravações de vídeo, por exemplo) não impede as outras.
for pkg in com.reau.app com.reau.app.lite; do
  destino="$OUT"
  if [ "$pkg" = com.reau.app.lite ]; then destino="$OUT/antigo-lite"; mkdir -p "$destino"; fi
  for pasta in diag sessions video; do
    if "$ADB" -s "$SERIAL" exec-out run-as "$pkg" tar c -C files "$pasta" 2>/dev/null | tar x -C "$destino" 2>/dev/null; then
      echo "pasta $pasta copiada de $pkg" >> "$OUT/INFO.txt"
    fi
  done
done
rmdir "$OUT/antigo-lite" 2>/dev/null || true

echo "coletado em $OUT"
echo "logcat-sistema.mascarado.txt (e os .mascarado.txt de sistema/) são as cópias sem o nome Wi-Fi do painel; ainda têm o resto do dado pessoal."
echo "sistema/ guarda o que o Android disse do Wi-Fi e da conectividade; o logcat guarda pouca história, então rode isto logo depois da corrida."
echo "ATENÇÃO: logcat-sistema.txt tem dado pessoal (nome, e-mails, redes Wi-Fi, apps), e sessions/ e video/ (se copiadas) mostram o mapa e por onde você andou: não compartilhe sem filtrar."
