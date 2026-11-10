# REau

English: [README.md](README.md) · Português (pt-BR): [README.pt-br.md](README.pt-br.md)

Aplicación Android que lleva el **Android Auto del propio teléfono** al tablero TFT de la moto, por el Wi-Fi del tablero, con la
pantalla del teléfono apagada. REau no dibuja ningún mapa: repite en el tablero lo que la app de navegación (Waze, Google Maps)
dibuja en Android Auto.

> **Compatibilidad:** funciona con el **Tripper Dash**. REau es un proyecto independiente, sin relación con Royal
> Enfield, y ni ella lo respalda ni lo mantiene.

## Cómo funciona

```
App de navegación ──▶ el Android Auto del teléfono ──(video H.264 por 127.0.0.1)──▶ REau
                                                                                    │ decodifica, recorta, reduce
                                                                                    ▼
                                                                 codificador 526×300 ──RTP──▶ tablero de la moto
```

REau se presenta a Android Auto como la unidad principal de un coche, usando el servidor de unidad principal que el propio
Android Auto ofrece en el modo de desarrollador. Recibe el video, ajusta la imagen a la pantalla del tablero y la envía por el
Wi-Fi del tablero.

## Qué hace

- Muestra la pantalla de Android Auto en el tablero, con un ajuste regulable (uno para las apps de navegación y otro para las demás pantallas,
  el menú de apps incluido).
- Envía al tablero la maniobra, la distancia, la hora de llegada y el tiempo que falta de la ruta.
- Envía también la música que suena (cuando la página del joystick tiene un control de música), las llamadas y el clima, para las tarjetas del tablero.
- Usa el joystick del manillar para controlar Android Auto (mapa, menú, música, llamadas y Gemini, que viene activado y funciona cuando concedes el micrófono): mientras suena una llamada, la izquierda contesta y la derecha o el
  centro rechazan; durante una llamada, el centro cuelga y arriba y abajo cambian el volumen.
- Un anillo de información sobre la imagen, a la derecha del tablero (viene apagado, con un interruptor): lluvia, viento, tormentas, UV, calidad del aire, el sol, las temperaturas del día y la batería, la señal y el GPS del teléfono, una cosa a la vez. Necesita la ubicación e internet para el clima.
- Habla portugués, inglés y español: sigue el idioma del teléfono, y los ajustes tienen un selector.
- Ajusta la calidad del video según la distancia a la próxima maniobra y la velocidad.
- Se enciende con una pulsación larga en el ícono de REau ("Encender") o desde un mosaico de los Ajustes rápidos, para encenderlo en casa, antes del casco.
- Opcional: muestra Android Auto en un navegador de la red local, protegido por un PIN.
- Guarda un registro de diagnóstico en el almacenamiento interno de la app (activado por defecto, con un interruptor en los
  ajustes), para analizarlo en el computador (`scripts/registro/`); nada sale del teléfono si no lo compartes. Grabar el video
  y las sesiones es opcional (Ajustes > Depuración): viene desactivado en la app que instalas para rodar y activado en la compilación de
  depuración; las grabaciones se quedan en el teléfono, salen por los mismos días y el mismo tope de tamaño del registro, y guardan el mapa,
  la ruta y los mensajes de Android Auto.

## Requisitos

- Un teléfono con Android 10 o más nuevo y **Android Auto** instalado.
- El modo de desarrollador de Android Auto activado, con el "servidor de unidad principal". REau abre esa pantalla (una vez cada vez que enciendes, con REau en pantalla) y tú enciendes el servidor a mano: con Android Auto 17.4 o más nuevo se apaga al terminar la sesión, así que lo enciendes de nuevo cada vez; con 17.3 o anterior, REau le pide a Android Auto que se conecte y no hay nada que encender.
- Un tablero compatible, con su Wi-Fi al alcance.

## Instalar y actualizar

Instala el APK publicado, `REau-0.3.0.apk` (applicationId `com.reau.app`, nombre "REau"). Es una sola app: REau sabe qué pantalla de Android Auto se muestra (mapa, menú, apps) por la propia imagen, con una red neuronal pequeña que corre en el teléfono.

¿Vienes de la antigua REau Lite (paquete `com.reau.app.lite`)? REau 0.3.0 es una app nueva para ti: rehaz los ajustes y el emparejamiento con el tablero y luego desinstala la Lite antigua (la app nueva muestra una línea discreta y, si Android Auto no responde, un aviso, mientras la Lite antigua siga instalada). ¿Vienes del REau anterior (paquete `com.reau.app`)? Solo actualiza encima: los ajustes y el emparejamiento se quedan. Dos instalaciones juntas compiten por el único servidor de Android Auto, que acepta una sesión a la vez.

## Compilación

Con JDK 21 y el Android SDK instalados:

```
./gradlew assembleRelease               # el APK: app/build/outputs/apk/release/
./gradlew assembleDebug                 # el APK de debug: app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest        # pruebas unitarias
```

Las pruebas instrumentadas (`app/src/androidTest`) corren en un emulador, contra un Android Auto falso y un tablero falso.

Hay un APK publicado en la página de Releases del proyecto; para compilar el tuyo, Gradle tiene que encontrar el Android SDK (`ANDROID_HOME`, o `sdk.dir` en `local.properties`), y, como el repositorio no trae la clave de firma del proyecto, `assembleRelease` solo firma con la clave de debug de Android si lo pides con `-PreauDebugSigned=true`. Las Releases oficiales se firman con la clave del proyecto, guardada fuera del repositorio; sin las variables de entorno `REAU_KEYSTORE` (la ruta absoluta del archivo de la clave), `REAU_KEY_ALIAS`, `REAU_KEYSTORE_PASSWORD` y `REAU_KEY_PASSWORD`, el empaquetado del release se detiene con un error.

## Privacidad

REau no tiene cuenta, ni anuncios, ni recoge datos. La pantalla **"REau y tus datos"**, dentro de la app, dice qué se queda en el
teléfono, qué sale y por qué. Lo que sale del teléfono va al tablero y, solo con el servidor de red opcional encendido, a otros
dispositivos de tu red local; los únicos viajes a internet son el del clima (Open-Meteo, para el tablero, que viene activado, o el anillo de información, que viene apagado: no pide nada hasta que concedas la ubicación, y cada uno tiene un interruptor) y el botón “Buscar actualizaciones” de los Ajustes, que solo consulta a GitHub cuando lo tocas. El permiso de ubicación también deja que Android entregue a REau la lista de redes Wi-Fi, para ver la del tablero, con la app detrás de otra (Android Auto, Waze) o con la pantalla apagada; REau no lee la posición para eso, y Android puede mostrar el ícono de ubicación durante la proyección.

## Seguridad

No manipules el teléfono mientras conduces. Usa el joystick del manillar, y solo cuando la moto esté en condiciones seguras.

## Historial de cambios

Todos los cambios importantes están en [CHANGELOG.es.md](CHANGELOG.es.md) (en inglés: [CHANGELOG.md](CHANGELOG.md); en portugués: [CHANGELOG.pt-br.md](CHANGELOG.pt-br.md)).

## Términos de uso

La app te pide aceptar los [términos de uso](TERMS_OF_USE.es.md) la primera vez que se abre (en inglés: [TERMS_OF_USE.md](TERMS_OF_USE.md); en portugués: [TERMS_OF_USE.pt-br.md](TERMS_OF_USE.pt-br.md)); están al final de la pantalla **"REau y tus datos"**, junto a los créditos.

## Contribuir y seguridad

REau es un proyecto de tiempo libre, con un único mantenedor. Cómo informar un error, ejecutar las pruebas y enviar un cambio está en [CONTRIBUTING.es.md](CONTRIBUTING.es.md) (en inglés: [CONTRIBUTING.md](CONTRIBUTING.md); en portugués: [CONTRIBUTING.pt-br.md](CONTRIBUTING.pt-br.md)). Un problema de seguridad no se informa en un issue público: mira [SECURITY.md](SECURITY.md) (solo en inglés).

## Licencia y créditos

REau es software libre bajo la **GNU Affero General Public License v3.0** ([LICENSE](LICENSE)). Incorpora código
de otros proyectos abiertos; el archivo [NOTICE](NOTICE) enumera cada uno, con su licencia y lo que se usó, y la app muestra
los mismos créditos en la pantalla **Créditos**, al final de **"REau y tus datos"**, donde también se da la dirección del
código fuente propio de REau (la AGPL pide que los usuarios de la app puedan obtenerlo).
