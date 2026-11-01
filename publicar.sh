#!/bin/sh
# Crea el repositorio público Bl4cksit0/wavoz y sube todas las versiones como Releases.
set -e
cd "$(dirname "$0")"
G=~/android-dev/bin/gh
R=/tmp/claude-1000/-home-bck/794e5436-fe9c-4bd7-b0f5-7bbaafdf021f/scratchpad/rel

$G auth setup-git
$G repo create wavoz --public --source=. --push \
  --description "Asistente de voz manos libres para WhatsApp (intercom de moto)"

git tag -f v2.0 67589b0
git tag -f v3.0 67589b0
git tag -f v3.1 3129438
git tag -f v3.2 0ab40da
git tag -f v3.3 a616947
git push -f origin --tags

$G release create v2.0 "$R/WaVoz-2.0.apk" --title "WaVoz 2.0" --latest=false \
  --notes "Lectura de mensajes y comandos por voz (escribir / responder / repetir)."
$G release create v3.0 "$R/WaVoz-3.0.apk" --title "WaVoz 3.0" --latest=false \
  --notes "Manos libres con pantalla apagada y auto-actualización desde GitHub."
$G release create v3.1 "$R/WaVoz-3.1.apk" --title "WaVoz 3.1" --latest=false \
  --notes "Responde sí/no al instante (offline), agrupa mensajes del mismo chat, voz offline más rápida."
$G release create v3.2 "$R/WaVoz-3.2.apk" --title "WaVoz 3.2" --latest=false \
  --notes "Busca actualización cada vez que se abre la app."
$G release create v3.3 "$R/WaVoz-3.3.apk" --title "WaVoz 3.3" --latest \
  --notes "Prueba de actualización automática."

echo
echo "Listo: https://github.com/Bl4cksit0/wavoz/releases"
