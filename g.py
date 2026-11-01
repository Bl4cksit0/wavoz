import json, sys
from vosk import Model, KaldiRecognizer, SetLogLevel
m = Model(sys.argv[1])
words = ["escribir","escribe","mensaje","responder","contestar","repetir","repite","[unk]"]
r = KaldiRecognizer(m, 16000, json.dumps(words))
print("ok")
