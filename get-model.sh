#!/bin/sh
# Descarga el modelo de voz offline (Vosk, español) que va dentro de la APK.
set -e
cd "$(dirname "$0")"
curl -L -o /tmp/vosk-es.zip https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip
rm -rf app/src/main/assets/model-es && mkdir -p app/src/main/assets
unzip -q /tmp/vosk-es.zip -d /tmp/vosk-es && mv /tmp/vosk-es/vosk-model-small-es-0.42 app/src/main/assets/model-es
rm -f app/src/main/assets/model-es/README && echo "es-small-0.42-v1" > app/src/main/assets/model-es/uuid
