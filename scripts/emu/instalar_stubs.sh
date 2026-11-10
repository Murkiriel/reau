#!/usr/bin/env bash
# Instala os stubs de teste SO no emulador emulator-5580, ou no que REAU_EMU_SERIAL nomear (so emuladores; todas as chamadas levam -s; nunca toca o celular real).
# Hoje: o stub do Android Auto (com.google.android.projection.gearhead, versionName 1.2.551000-stub) que os testes
# esperam. A imagem google_apis traz um stub proprio (1.2.531830-stub, em /product/priv-app), de outra versao e
# assinado pelo Google; como e app de sistema, so se troca o arquivo na particao /product (overlayfs gravavel).
# Os apps de voz (WhatsApp, Telegram, Signal) NAO precisam de stub: os testes so comparam os nomes dos pacotes.
#
# Uso:  REAU_AVD=reau_test_api34_gapps scripts/emu/subir_emulador.sh --frio    # *_gapps sobe com -writable-system
#       scripts/emu/instalar_stubs.sh
# O emulador precisa subir com -writable-system (emulator -writable-system; subir_emulador.sh faz isso nos AVDs *_gapps
# ou com --gravavel) em TODO boot: sem a flag o overlay nao e montado e volta o stub da imagem (1.2.531830-stub).
# A substituicao fica no disco do AVD, entao depois da 1a vez o script nao faz nada. Na 1a vez reinicia o emulador 2x.
set -euo pipefail
cd "$(dirname "$0")"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
SERIAL="${REAU_EMU_SERIAL:-emulator-5580}"   # outro emulador (ex.: emulator-5584, o AVD de 16 KB): REAU_EMU_SERIAL=emulator-5584
case "$SERIAL" in emulator-*) ;; *) echo "REAU_EMU_SERIAL=$SERIAL: so emuladores, nunca o celular" >&2; exit 1 ;; esac
ADB="$ANDROID_HOME/platform-tools/adb"
PKG=com.google.android.projection.gearhead
WANT=1.2.551000-stub
adb_e() { "$ADB" -s "$SERIAL" "$@"; }

"$ADB" devices | awk -v s="$SERIAL" '$1==s && $2=="device" {f=1} END {exit !f}' || { echo "$SERIAL nao esta no ar" >&2; exit 1; }
[ "$(adb_e shell getprop ro.kernel.qemu | tr -d '\r')" = "1" ] || { echo "$SERIAL nao e um emulador" >&2; exit 1; }

versao() { adb_e shell dumpsys package "$PKG" | tr -d '\r' | sed -n 's/^ *versionName=//p' | head -1; }
espera_boot() {
  sleep 8
  for _ in $(seq 1 100); do
    [ "$(adb_e shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && { sleep 5; return 0; }
    sleep 3
  done
  echo "boot nao terminou" >&2; return 1
}
reinicia() { adb_e reboot; espera_boot; }
# o ro.kernel.qemu continua valendo depois de "adb root"; o remount precisa de root
raiz() { adb_e root >/dev/null; sleep 3; }

if [ "$(versao)" = "$WANT" ]; then echo "stub $WANT ja instalado em $SERIAL"; exit 0; fi

APK="$PWD/stub_android_auto/build/gearhead-stub.apk"
[ -f "$APK" ] || stub_android_auto/construir.sh >/dev/null

raiz
saida=$(adb_e remount 2>&1 || true)
if echo "$saida" | grep -q "bootloader unlocked"; then
  echo "o emulador precisa subir com: REAU_AVD=... scripts/emu/subir_emulador.sh --frio" >&2; exit 1
fi
if echo "$saida" | grep -qi "reboot"; then   # primeira vez: o overlayfs so vale depois de um boot
  reinicia; raiz; adb_e remount >/dev/null
fi
D=/product/priv-app/AndroidAutoStubPrebuilt
adb_e push "$APK" "$D/AndroidAutoStubPrebuilt.apk" >/dev/null
adb_e shell "rm -rf $D/oat; restorecon -R $D; chmod 644 $D/AndroidAutoStubPrebuilt.apk"
reinicia
got=$(versao)
[ "$got" = "$WANT" ] || { echo "versao lida: '$got', esperada $WANT" >&2; exit 1; }
adb_e shell input keyevent KEYCODE_WAKEUP || true
adb_e shell wm dismiss-keyguard || true
echo "stub do Android Auto $got instalado em $SERIAL"
