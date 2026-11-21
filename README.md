# Transfer

Comparte archivos entre el teléfono, la TV y otros dispositivos de tu Wi-Fi.
El teléfono crea la página y los demás abren `http://transfer.local:4747`
(o la dirección IP que muestra la app) en el navegador.

- **App Android**: descarga `Transfer.apk` en [Releases](../../releases/latest).
  Se compila sola con GitHub Actions en cada cambio. Código en `app/`.
- **Navegador** para Android y Android TV: descarga `Navegador-Transfer.apk` en
  [Releases](../../releases/latest). Abre `transfer.local` aunque la TV no lo entienda,
  encuentra solo las apps de tu red, sube y descarga archivos. Código en `browser/`
  (ver `browser/LEEME.md`).
- **Versión Python** (Termux o PC): `python/transfer.py`. Instrucciones en `python/LEEME.md`.
