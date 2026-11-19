# Transfer: pasar archivos entre dispositivos por Wi-Fi

## Por qué no es solo un archivo HTML

Un archivo `.html` abierto en el navegador del teléfono no puede recibir conexiones de otros dispositivos ni crear el nombre `transfer.local`. Para eso hace falta un pequeño **servidor**. Por eso la solución es `transfer.py`: un solo archivo de Python que crea la página web, guarda los archivos y anuncia el nombre `transfer.local` en tu Wi-Fi. No necesita internet ni instalar librerías.

Todos los dispositivos deben estar **en la misma red Wi-Fi**.

## Opción A: en el teléfono Android (con Termux)

1. Instala **Termux** desde F-Droid (la versión de Play Store está desactualizada).
2. Abre Termux y escribe, una línea a la vez:
   ```
   pkg install python
   termux-setup-storage
   ```
   (acepta el permiso de almacenamiento).
3. Copia `transfer.py` a la carpeta Descargas del teléfono y ejecútalo:
   ```
   python ~/storage/downloads/transfer.py
   ```
4. Te preguntará el puerto. Presiona **Enter** para usar el 4747, o escribe otro número (por ejemplo 8080).
5. En la pantalla aparecerá algo como:
   ```
   Abre en otro dispositivo:  http://transfer.local:4747
   Si no abre, usa la IP:     http://192.168.1.25:4747
   ```
6. En la TV o en otro dispositivo, abre el navegador y escribe esa dirección.

Los archivos quedan en **Descargas/Transfer** del teléfono.

> El número del final (`:4747`) es el puerto que elegiste. En Android, sin root, no se pueden usar puertos menores a 1024.

## Opción B: en una PC (Windows, Mac o Linux)

1. Instala Python 3 (en Windows, desde python.org, marcando "Add to PATH").
2. Abre una terminal en la carpeta de `transfer.py` y ejecuta:
   ```
   python transfer.py
   ```
   Te preguntará el puerto (Enter = 4747). Luego, en otro dispositivo abre **http://transfer.local:4747** (o con el puerto que elegiste).
3. Si Windows pregunta por el firewall, permite el acceso en redes privadas.

Los archivos quedan en la carpeta **Transfer** de tu usuario.

## Qué puedes hacer en la página

- **Subir**: toca el recuadro o arrastra archivos. Se ve el progreso de cada uno.
- **Descargar** o **Ver** (fotos, videos y PDF se abren en el navegador).
- **Borrar** con la ✕.
- La lista se actualiza sola cada 5 segundos en todos los dispositivos.
- Si hay internet, aparece un código QR para abrirla desde otro teléfono.

## Si `transfer.local` no abre

Los nombres `.local` funcionan bien en iPhone, Mac, Windows 10/11 y Linux, pero **muchas TV y algunos Android no los reconocen**. En ese caso escribe la dirección con números (la IP) que aparece en la pantalla, por ejemplo `http://192.168.1.25:4747`. Funciona igual.

Otros problemas comunes:
- El dispositivo está en otra red (por ejemplo, Wi-Fi de invitados).
- El router tiene activado el "aislamiento de clientes" (AP isolation).
- El teléfono apagó la pantalla y Android cerró Termux: en la notificación de Termux toca **Acquire wakelock**.

## Opciones

```
python transfer.py --port 8080          # elegir el puerto sin que pregunte
python transfer.py --name casa          # usar http://casa.local
python transfer.py --dir /ruta/carpeta  # cambiar la carpeta de archivos
```

Nota: cualquiera conectado a tu Wi-Fi puede abrir la página, así que úsalo en redes de confianza y cierra el programa (Ctrl + C) al terminar.
