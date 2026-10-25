# NeuDNS

Aplicación para Android que **bloquea anuncios y rastreadores únicamente en las aplicaciones
seleccionadas**, sin necesidad de root y sin afectar al resto del dispositivo.

- Basta con seleccionar las aplicaciones en una lista y pulsar **Activar**.
- El resto de aplicaciones (banca, trabajo, etc.) funciona sin cambios.
- Las consultas DNS de las aplicaciones seleccionadas se envían **cifradas** (DNS-over-HTTPS) a
  AdGuard, Mullvad o un servidor propio, que no resuelven los dominios de publicidad.
- APK de menos de 120 KB, sin bibliotecas externas, con permisos mínimos y sin anuncios ni telemetría.

## Instalación

1. Descargar desde el dispositivo la última versión de `NeuDNS-vX.Y.apk` en
   [Releases](../../releases).
2. Abrir el archivo. Android solicitará permiso para instalar aplicaciones de ese origen.
3. Abrir **NeuDNS**, seleccionar las aplicaciones y pulsar **Activar**. La primera vez, Android
   solicitará autorización para crear la VPN.

Requiere Android 11 o superior.

## Uso

| Acción | Procedimiento |
|---|---|
| Activar o desactivar sin abrir la aplicación | Añadir el botón **NeuDNS** a los ajustes rápidos (desplegar la barra de notificaciones y pulsar el icono de edición). |
| Mantenerlo siempre activo | Se reactiva automáticamente al reiniciar el dispositivo. Para mayor seguridad: Ajustes → Red → VPN → NeuDNS → **VPN siempre activa**. **No** active «Bloquear conexiones sin VPN»: Android dejaría sin internet a todas las aplicaciones no seleccionadas (la aplicación lo detecta y muestra un aviso). |
| Añadir o quitar aplicaciones | Marcarlas o desmarcarlas en la lista; si el filtro está activo, el cambio se aplica en aproximadamente un segundo. |
| Cambiar el contenido bloqueado | Seleccionar el servidor en «Contenido bloqueado». |
| Usar un servidor propio | Seleccionar **Servidor personalizado** y escribir su dirección DNS-over-HTTPS (por ejemplo, `https://dns.nextdns.io/abc123`). Solo se admite `https`. Con un servidor personalizado no se pueden permitir dominios ni pausar el bloqueo, porque no hay un servidor sin bloqueo del mismo proveedor. |
| Saber qué se bloquea | Pulsar **Ver qué se bloquea → Empezar**, usar la aplicación que falla y volver: durante 10 minutos se anotan los dominios bloqueados. Al tocar uno se ofrece permitirlo. La lista solo está en memoria (ver «Seguridad y privacidad»). |
| Desbloquear un dominio | Si una aplicación seleccionada deja de funcionar porque el servidor bloquea algo que necesita, pulsar **Dominios permitidos → Añadir** y escribir el dominio (por ejemplo, `ejemplo.com`), o tocarlo en la lista de **Ver qué se bloquea**. Se aplica al momento, también a sus subdominios, y se resuelve con el servidor sin bloqueo del mismo proveedor (AdGuard o Mullvad). Tocar un dominio de la lista permite quitarlo. |
| Bloquear un dominio | Pulsar **Dominios bloqueados → Añadir** y escribir el dominio. Se responde como inexistente sin consultar al servidor, también a sus subdominios. Si un dominio está en las dos listas (por ejemplo, se permite `ejemplo.com` y se bloquea `ads.ejemplo.com`), se aplica la entrada más concreta. |
| Pausar el bloqueo | Con el filtro activo, pulsar **Pausar el bloqueo** y elegir 5 minutos, 15 minutos o 1 hora. Durante la pausa las consultas se resuelven, también cifradas, con el servidor sin bloqueo del mismo proveedor, y al terminar el bloqueo se reanuda solo. **Reanudar el bloqueo ahora** la termina antes. |

### Servidores

| Servidor | Bloquea |
|---|---|
| AdGuard | Anuncios y rastreadores |
| AdGuard Familiar | Anuncios, rastreadores y contenido para adultos |
| Mullvad Base | Anuncios, rastreadores y malware |
| Mullvad Adblock | Anuncios y rastreadores |
| Mullvad Extended | Lo de Base y redes sociales |
| Mullvad Family | Lo de Base, contenido para adultos y apuestas |
| Mullvad All | Lo de Base, redes sociales, contenido para adultos y apuestas |
| Mullvad sin bloqueo | Nada (solo cifra el DNS) |
| Servidor personalizado | Lo que esté configurado en ese servidor |

Con los servidores que bloquean redes sociales, las aplicaciones seleccionadas no podrán usar
servicios de esas redes (por ejemplo, iniciar sesión con Facebook).

Mullvad solo admite HTTP/2. La aplicación lo utiliza a través de la pila de red de Android
(HttpEngine), disponible en Android 14 o superior y en Android 11–13 con las actualizaciones del
sistema de Google Play. En los dispositivos que no la incluyen solo se ofrecen AdGuard y el
servidor personalizado.

## Funcionamiento

La aplicación crea una VPN **local** (el tráfico no se envía a ningún servidor VPN) a la que solo
se asignan las aplicaciones seleccionadas. Esta VPN únicamente recibe el tráfico DNS; la
navegación, el vídeo y el resto del tráfico siguen por la conexión habitual, por lo que no hay
pérdida de velocidad ni consumo adicional de batería.

- Compatible con IPv4 e IPv6 y con DNS sobre UDP y TCP.
- Las respuestas se almacenan en caché respetando su TTL, lo que reduce las consultas repetidas.
- El hilo que lee el túnel permanece en espera sin límite de tiempo mientras no hay consultas, sin
  despertar la CPU.
- Si el servidor no responde (mala cobertura, cambio de red), se devuelve la última respuesta
  conocida aunque haya caducado, con un TTL de 30 s, durante un máximo de un día (RFC 8767). Si
  no hay ninguna, la aplicación cliente recibe un error inmediato en lugar de quedar a la espera.
- Si una aplicación intenta usar su propio DNS (Google, Cloudflare, Quad9, OpenDNS, AdGuard,
  CleanBrowsing, NextDNS, Mullvad, Control D, DNS.SB) o DNS cifrado (DoH/DoT hacia esos
  servidores, Firefox, descubrimiento automático DDR), la consulta se redirige o se bloquea para
  que pase por el filtro.
- Al cambiar de red (wifi ↔ datos) se vacía la caché y no se reutilizan conexiones de la red
  anterior.
- Con el filtro activo, la pantalla muestra cuántas consultas se han contestado y cuántas se han
  bloqueado desde que se activó. Una consulta cuenta como bloqueada si la bloquea el usuario o si
  el servidor responde como lo hacen AdGuard y Mullvad al bloquear (dirección `0.0.0.0` o `::`, o
  su registro SOA de bloqueo). Un dominio que de verdad no existe no cuenta. Con AdGuard Familiar,
  el contenido para adultos se redirige a una página propia y no siempre se cuenta.

### Alcance: solo las aplicaciones seleccionadas

- Android aplica la VPN por identificador de usuario (UID), no por paquete. Las aplicaciones del
  sistema (Ajustes y otras integradas en Android) comparten el UID del propio sistema, por lo que
  no aparecen en la lista ni se añaden nunca: filtrarlas afectaría a servicios del sistema.
- Si una aplicación comparte UID con otras (poco habitual, algunas de fabricante), la lista lo
  indica: el filtro se aplicará también a esas otras.
- Al resto de aplicaciones solo se les envía su tráfico habitual por su red habitual. Aun así, la
  interfaz de la VPN es visible para todo el dispositivo, y alguna aplicación (por ejemplo, de
  banca) puede detectar que «hay una VPN» aunque no la utilice.

### Limitaciones conocidas

- **Una sola VPN simultánea**: es una restricción de Android. Si se activa otra VPN, el filtro se
  desactiva.
- **DNS privado con servidor propio**: si en Ajustes → Red → DNS privado hay un nombre de servidor
  configurado, Android cifra el DNS por su cuenta y el filtro no puede inspeccionarlo. La
  aplicación muestra un aviso; debe seleccionarse «Automático» o «Desactivado».
- **Anuncios servidos desde el mismo dominio que el contenido** (por ejemplo, los de YouTube, que
  llegan por `youtubei.googleapis.com` y `googlevideo.com`, igual que los vídeos): no pueden
  bloquearse mediante DNS sin romper la aplicación. Ningún bloqueador DNS (AdGuard DNS, NextDNS,
  Pi-hole…) puede hacerlo; solo un bloqueador integrado en la propia aplicación o navegador. Por
  eso YouTube, Facebook, Instagram, Threads, Messenger, TikTok, X, Snapchat, Reddit, Pinterest,
  LinkedIn, Twitch y Spotify no aparecen en la lista; si alguna está instalada, la pantalla lo
  indica junto con el motivo. Para YouTube, la alternativa es usarlo en Firefox para Android con
  la extensión uBlock Origin.
- Una aplicación que utilice DNS cifrado hacia un servidor poco conocido, o en modo estricto (por
  ejemplo, Chrome con «DNS seguro» y un proveedor específico), puede eludir el filtro o quedarse
  sin conexión. En ese caso, debe desactivarse dicha opción en la aplicación afectada.

## Seguridad y privacidad

- **Sin registros**: la aplicación no almacena ni envía los dominios consultados. Solo los conoce el
  servidor DNS seleccionado. El recuento de consultas es solo un número en memoria. La lista de
  **Ver qué se bloquea** se apunta únicamente cuando el usuario la pide, durante 10 minutos, solo
  incluye los dominios bloqueados (como mucho 200), nunca se escribe en disco ni sale del
  dispositivo, y se borra al pulsar **Detener y borrar**, al desactivar el filtro o media hora
  después de terminar.
- **Solo HTTPS**, con certificados de las autoridades del sistema; no se aceptan certificados
  instalados por el usuario ni redirecciones.
- **Validación completa**: cada respuesta debe corresponder exactamente a la consulta realizada,
  tener el tipo `application/dns-message` y no superar el tamaño máximo de un mensaje DNS. En caso
  contrario, se descarta y no se almacena en caché.
- **Resistencia a abusos**: un paquete malformado nunca provoca el cierre de la aplicación, y las
  colas están limitadas para que una aplicación que genere consultas masivas no agote la memoria.
- **Permisos mínimos**: Internet, estado de la red e inicio con el sistema. Otras aplicaciones solo
  pueden abrir la pantalla principal; el servicio VPN y el botón de ajustes rápidos solo pueden ser
  utilizados por el sistema.
- **Copias de seguridad**: solo incluyen la configuración (aplicaciones seleccionadas, servidor y
  dominios permitidos y bloqueados).

## Compilación

Requiere JDK 17 y el SDK de Android.

```bash
./gradlew testDebugUnitTest assembleRelease
```

La APK se genera en `app/build/outputs/apk/release/`. Si no se proporciona una clave propia, se
firma con la clave de depuración.

### Firma de versiones

La configuración de compilación lee la clave de firma de variables de entorno, de modo que nunca
se almacena en el repositorio. Para generar una clave (una única vez; debe guardarse en un lugar
seguro, ya que si se pierde los usuarios tendrán que desinstalar la aplicación para actualizarla):

```bash
keytool -genkeypair -v -keystore neudns.jks -alias neudns -keyalg RSA -keysize 4096 -validity 10000
```

| Variable | Valor |
|---|---|
| `SIGNING_KEYSTORE_FILE` | Ruta al archivo `neudns.jks` |
| `SIGNING_STORE_PASSWORD` | Contraseña del almacén |
| `SIGNING_KEY_ALIAS` | `neudns` |
| `SIGNING_KEY_PASSWORD` | Contraseña de la clave |

## Publicación de versiones

El workflow `.github/workflows/release.yml` valida el wrapper de Gradle, ejecuta los tests y lint,
firma la APK y la publica en Releases, junto con su suma `.sha256`, cada vez que se sube una
etiqueta `vX.Y`. Las actions están fijadas por commit, con permisos mínimos, y la publicación se
cancela si faltan los secretos de firma.

En GitHub → Settings → Secrets and variables → Actions deben crearse estos secretos:

| Secreto | Valor |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | Contenido de `neudns.jks` en base64 (PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("neudns.jks"))`) |
| `SIGNING_STORE_PASSWORD` | Contraseña del almacén |
| `SIGNING_KEY_ALIAS` | `neudns` |
| `SIGNING_KEY_PASSWORD` | Contraseña de la clave |

Para cada versión deben incrementarse `versionCode` y `versionName` en `app/build.gradle.kts`; la
etiqueta debe coincidir con `versionName` (por ejemplo, `v1.1`):

```bash
git tag v1.1
git push origin v1.1
```

La integridad de la APK descargada puede comprobarse con:

```bash
sha256sum -c NeuDNS-v1.1.apk.sha256
```

## Estructura

```
app/src/main/java/io/github/neumanv/neudns/
├── Prefs.kt              Configuración y lista de servidores
├── ui/MainActivity.kt    Pantalla principal
├── ui/AppListAdapter.kt  Lista de aplicaciones con carga de iconos en segundo plano
├── vpn/                  Servicio VPN, túnel, botón de ajustes rápidos e inicio con el sistema
├── dns/                  Cliente DoH, caché por TTL, análisis de mensajes DNS y recuento de bloqueos
└── net/                  Paquetes IPv4/IPv6 y TCP mínimo para DNS
```

## Licencia

Dominio público bajo [CC0 1.0 Universal](LICENSE).
