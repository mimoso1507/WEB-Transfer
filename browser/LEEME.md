# Navegador (para apps de tu red local)

Navegador para Android y Android TV pensado para abrir apps de tu Wi-Fi como
**Transfer**. Descarga `Navegador.apk` en [Releases](../../releases/latest).

## Qué tiene

- **Nombres `.local`**: abre `transfer.local:4747` aunque la TV no los entienda
  (los resuelve él mismo por mDNS).
- **Busca solo en tu red**: la pantalla de inicio muestra las páginas que encuentra
  en el Wi-Fi (puertos 4747, 8080, 8000, 8888, 5000, 3000 y 80) y marca las de Transfer.
- **Subir archivos**: abre el selector de archivos del equipo (en TV necesitas un
  explorador instalado, por ejemplo "Files" o "X-plore").
- **Descargar**: guarda en **Descargas/Navegador** con el progreso en una notificación.
- **Control remoto**: flechas para moverse, OK para abrir, Atrás para volver. Lo
  seleccionado se resalta en azul. La tecla Menú lleva a la barra de direcciones.
- **Videos en pantalla completa**, favoritos (★) y recientes.
- Acepta páginas `http://` y certificados propios de apps locales (pregunta antes).
- Aparece en el menú de apps de Android TV.

## Código

`browser/` es un módulo del mismo proyecto que `app/` (Java puro, sin librerías).
GitHub Actions compila los dos APK en cada cambio a `main`.
