# Navegador y Archivos (para tu red local)

App para Android y Android TV con dos herramientas; al abrirla preguntas cuál usar.
El **navegador** está pensado para abrir apps de tu Wi-Fi como **Transfer**. Descarga `Navegador-Archivos-v2.apk` en [Releases](../../releases/latest).

## Qué tiene

- **Escribir dirección**: botón azul grande en la barra (y en la página de inicio, o con la tecla Menú) que abre un cuadro para escribir o pegar cualquier `http://`, `https://` o nombre `.local`. Si escribes palabras, busca en internet.
- **Nombres `.local`**: abre `transfer.local:4747` aunque la TV no los entienda
  (los resuelve él mismo por mDNS).
- **Busca solo en tu red**: la pantalla de inicio muestra las páginas que encuentra
  en el Wi-Fi (puertos 4747, 8080, 8000, 8888, 5000, 3000 y 80) y marca las de Transfer.
- **Descargas con estado**: botón ⬇ y una barra azul arriba muestran el avance, la velocidad y el
  tamaño. En el panel de Descargas puedes cancelar, reintentar, abrir, reproducir o ver el archivo en Archivos.
- **Instalar APK desde el navegador**: al terminar de descargar un `.apk` pregunta si instalarlo,
  y en el panel de Descargas queda el botón **Instalar** (sin pasar por Archivos).
- **Pestañas**, buscar texto en la página, ver el sitio como computadora, historial, tamaño del texto,
  pantalla completa, compartir enlace, borrar datos y menú al mantener pulsado un enlace o imagen (menú ⋮).
- **Subir archivos**: abre el selector de archivos del equipo (en TV necesitas un
  explorador instalado, por ejemplo "Files" o "X-plore").
- **Descargar**: guarda en **Descargas/Navegador** con el progreso en una notificación.
- **Control remoto**: flechas para moverse, OK para abrir, Atrás para volver. Lo
  seleccionado se resalta en azul. La tecla Menú lleva a la barra de direcciones.
- **Videos en pantalla completa**, favoritos (★) y recientes.
- Acepta páginas `http://` y certificados propios de apps locales (pregunta antes).
- Aparece en el menú de apps de Android TV.

## Archivos (explorador)

- Navega por el almacenamiento interno, tarjeta SD / USB y carpetas frecuentes.
- **Copiar, cortar, pegar, borrar, renombrar, crear carpeta**, seleccionar varios y compartir.
- **Instalar apps**: toca un `.apk` (la primera vez pide el permiso "instalar apps desconocidas").
- Abrir archivos con otras apps, ver detalles, ordenar y mostrar archivos ocultos.
- Mantén presionado un archivo (o la tecla Menú del control) para ver sus opciones.
- La primera vez pide el permiso para ver todos los archivos.

## Reproductor de video y audio

- Toca un video o audio en Archivos y se reproduce con controles (pausa, adelantar y
  atrasar 10 s, siguiente, velocidad, pista de audio y subtítulos). Reproduce toda la
  carpeta en orden y recuerda dónde te quedaste.
- Formatos: MP4, MKV, WebM, AVI, MOV, TS/M2TS, MP3, FLAC, OGG/Opus, AAC, WAV y más.
  Audio extra (AC3, E-AC3, DTS, TrueHD) con decodificadores FFmpeg incluidos.
- **VOD y streaming**: enlaces `.m3u8` (HLS), `.mpd` (DASH), Smooth Streaming y listas
  `.m3u`/`.pls`, locales o de internet. Usa el botón **▶ Enlace** de Archivos.
- En el navegador, al abrir un enlace de video aparece la opción Reproducir o Descargar.
- Control remoto: OK muestra los controles, izquierda/derecha adelanta o atrasa.

## Accesos directos

En el navegador, Menú (⋮) > Crear acceso directo crea un acceso a la página actual que se abre
siempre con este navegador. También queda en **Inicio > Mis apps web**. En teléfonos y tablets aparece como una app más. Algunos
lanzadores de Android TV no permiten accesos directos nuevos; ahí usa favoritos (★).

## Código

`browser/` es un módulo del mismo proyecto que `app/` (Java puro, sin librerías).
GitHub Actions compila los dos APK en cada cambio a `main`.
