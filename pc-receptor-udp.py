"""Receptor de video para raspberry-emisor.py (RTP/H.264 sobre UDP, puerto 5000).

Requisitos en la PC:
  - OpenCV con soporte de GStreamer (el python3-opencv de apt lo trae;
    el wheel opencv-python de pip NO).
  - Plugins de GStreamer: gstreamer1.0-libav, plugins-good y plugins-bad.

Si el receptor arranca despues que el emisor, puede tardar unos segundos
en mostrar imagen (espera el siguiente fotograma clave).
"""
import sys
import cv2

PUERTO_UDP = 5000  # Debe coincidir con PUERTO_UDP del emisor

PIPELINE = (
    f'udpsrc port={PUERTO_UDP} caps="application/x-rtp, media=video, '
    'clock-rate=90000, encoding-name=H264, payload=96" ! '
    "rtph264depay ! h264parse ! avdec_h264 ! videoconvert ! "
    "video/x-raw, format=BGR ! appsink drop=1 max-buffers=1"
)

cap = cv2.VideoCapture(PIPELINE, cv2.CAP_GSTREAMER)
if not cap.isOpened():
    sys.exit("[RECEPTOR] No se pudo abrir el pipeline. "
             "Revisa OpenCV con GStreamer y los plugins instalados.")

print(f"[RECEPTOR] Esperando video RTP/H.264 en UDP {PUERTO_UDP}. "
      "Presiona 'q' en la ventana (o Ctrl+C) para salir.")

try:
    while True:
        ok, frame = cap.read()
        if ok:
            cv2.imshow("Receptor de Video - Raspberry Pi", frame)
        if cv2.waitKey(1) & 0xFF == ord('q'):
            break
except KeyboardInterrupt:
    pass
finally:
    cap.release()
    cv2.destroyAllWindows()
    print("[RECEPTOR] Programa finalizado limpiamente.")
