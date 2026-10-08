# Gravador OCR (Android)

Grava vídeo e, ao mesmo tempo, lê textos pela câmera (OCR offline com ML Kit).
Quando aparece uma palavra-chave, o celular apita, vibra e fala a palavra.
Se a gravação parar sozinha, ele tenta retomar e toca um alarme se não conseguir.

Este branch é só do app (separado do resto do repositório). O GitHub Actions compila,
roda os testes unitários e os testes no emulador (API 30 e 34) a cada push.
