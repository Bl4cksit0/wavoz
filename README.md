# WaVoz

Asistente de voz manos libres para WhatsApp (pensado para usar con intercom de casco de moto).

- Lee en voz alta los mensajes de los contactos/grupos elegidos.
- Comandos de voz: **"escribir"**, **"responder"**, **"repetir"**.
- Dictado con fin por 5 s de silencio y confirmación: **enviar / seguir / borrar / cancelar**.
- Se actualiza solo desde los Releases de este repositorio.

## Compilar
```
gradle assembleRelease
```

El modelo de voz offline (Vosk, español, ~40 MB) no va dentro de la APK: la app lo descarga
la primera vez (por Wi-Fi, o por datos si lo pedís en Ajustes) y lo guarda en el teléfono.
