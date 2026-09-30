# Loopera

Loopera es una loopera para Android pensada para usar en clases de música. Un pulso
visual marca el tiempo, grabás con el micrófono manteniendo apretado un botón y, al
soltarlo, lo que tocaste se suma como una capa nueva que se repite en loop.

## Qué hace

- **Pulso visual (no suena ningún clic).** Un círculo grande late en cada tiempo:
  se pone rojo en el tiempo 1 de cada compás y celeste en los demás, con el número de
  tiempo adentro. Al lado se ve "Compás X de N · tiempo Y", una fila de puntitos por
  compás y una barra con el avance del loop. La animación está compensada por la
  latencia de salida, así que coincide con lo que se escucha.
- **Tempo y largo.** De 40 a 200 BPM, con botones − y + (de a 2), un deslizador y el
  botón **Marcar tempo**: tocalo al ritmo y calcula el BPM con el promedio de los últimos
  toques (si pasan más de 2,5 s entre toques, empieza de nuevo). El loop puede durar 1, 2,
  3 o 4 compases de 4/4. Mientras haya capas grabadas, tempo y largo quedan fijos.
- **Grabación.** Tocá **Activar micrófono** (la primera vez te pide permiso) y mantené
  apretado **GRABAR** mientras tocás. Al soltar, la grabación se ubica en el lugar del
  loop donde la tocaste. Si el pulso estaba parado, apretar GRABAR lo arranca en el
  compás 1. Si grabás más de una vuelta, queda la última.
- **Capas.** Cada capa ("Capa 1", "Capa 2"…) tiene su color, un interruptor para
  prenderla o apagarla, volumen de 0 a 150 %, un botón para borrarla y su forma de onda
  sobre todo el loop, con la posición actual. Arriba hay un botón para borrar todas.
- **Ajuste de sincronía.** Si las capas nuevas quedan atrasadas respecto del pulso, abrí
  "Ajuste de sincronía" y subí el valor (0 a 300 ms). Se aplica a las grabaciones
  siguientes.

> **Usá auriculares.** Si el loop sale por el parlante, el micrófono lo vuelve a grabar
> y cada capa nueva arrastra las anteriores.

Cuando la app pasa a segundo plano, el pulso se detiene y se libera el micrófono. Las
capas quedan guardadas mientras la app siga abierta.

## Cómo instalar el APK en el celular

Cada vez que se sube un cambio a `main`, GitHub compila la app y publica el APK en el
Release **latest** del repositorio.

1. Desde el celular, abrí la página del repositorio en GitHub y entrá en
   **Releases → latest** (o directamente
   `https://github.com/LucaPoltiGit/taller-musica/releases/tag/latest`).
2. En **Assets**, tocá `loopera.apk` para descargarlo.
3. Abrí el archivo descargado (desde la notificación o desde la app **Archivos /
   Descargas**).
4. La primera vez, Android avisa que no permite instalar apps de fuentes desconocidas:
   tocá **Configuración**, activá **Permitir de esta fuente** para el navegador o la app
   de archivos que estés usando, y volvé.
5. Tocá **Instalar**. Si Play Protect muestra un aviso, elegí **Instalar de todas
   formas** (la app no está publicada en Play Store).
6. Abrí **Loopera**, conectá los auriculares y tocá **Activar micrófono**.

Para actualizar, repetí los pasos con el APK nuevo: se instala encima del anterior sin
tener que desinstalar.

Requiere Android 8.0 o más nuevo.

## Para desarrollar

- Kotlin + Jetpack Compose (Material 3), una sola Activity.
- `LoopEngine` (audio): `AudioTrack` en PCM float a 48 kHz y baja latencia, alimentado
  por un hilo de prioridad `URGENT_AUDIO`; `AudioRecord` mono a 48 kHz grabando todo el
  tiempo en un buffer circular con marcas de tiempo. El reloj es el contador de frames de
  salida.
- `LooperViewModel` expone el estado de la pantalla con `StateFlow`.
- La lógica pura (tempo, posición del loop, ubicación de las grabaciones) está en
  `core/` y tiene tests de JVM.

```bash
./gradlew testDebugUnitTest   # tests
./gradlew assembleDebug       # APK en app/build/outputs/apk/debug/
```
