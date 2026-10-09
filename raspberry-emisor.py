import os
import sys
import time
import cv2
import socket 

# IPs posibles de la PC del oficial de seguridad, una por red conocida:
# hotspot del telefono y Wi-Fi regular. Se puede forzar con IP_RECEPTOR=...
RECEPTORES = ["192.168.43.94", "10.24.30.235"]

def elegir_receptor():
    """Devuelve IP_RECEPTOR si esta definida; si no, la candidata que este en
    la misma subred (/24) que el Pi en la red a la que esta conectado."""
    forzada = os.environ.get("IP_RECEPTOR")
    if forzada:
        return forzada
    for ip in RECEPTORES:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect((ip, 9))  # UDP: solo consulta la ruta, no envia nada
            if s.getsockname()[0].rsplit(".", 1)[0] == ip.rsplit(".", 1)[0]:
                return ip
        except OSError:
            pass
        finally:
            s.close()
    return RECEPTORES[0]

IP_PC_GUARDIA = elegir_receptor()

PUERTO_UDP = 5000  # Debe coincidir con PUERTO_UDP de pc-receptor-udp.py

# Fuente de video. Por defecto: webcam USB (forzando memoria normal, formato YUY2,
# para evitar el DMABuf que GStreamer 1.28 prefiere por defecto en v4l2src).
# Para pruebas se puede cambiar con la variable de entorno FUENTE.
FUENTE = os.environ.get(
    "FUENTE",
    "v4l2src device=/dev/video0 ! video/x-raw, format=YUY2, width=640, height=480, framerate=30/1",
)

# Pipeline solo para tomar la foto de referencia (sin enviar nada por la red)
GSTREAMER_FOTO = (
    f"{FUENTE} ! videoconvert ! video/x-raw, format=BGR ! appsink drop=1 max-buffers=1"
)

# Pipeline en vivo: una rama va a OpenCV (appsink) y otra se envia al receptor
# como video RTP/H.264 por UDP. No hace falta que el receptor este corriendo:
# el emisor arranca igual y el receptor puede unirse en cualquier momento
# (key-int-max y config-interval repiten los parametros del codec cada ~1 s).
GSTREAMER_PIPELINE = (
    f"{FUENTE} ! videoconvert ! tee name=t "
    "t. ! queue ! videoconvert ! video/x-raw, format=BGR ! appsink drop=1 max-buffers=1 "
    "t. ! queue leaky=downstream max-size-buffers=2 ! videoconvert ! video/x-raw, format=I420 ! "
    "x264enc tune=zerolatency speed-preset=ultrafast bitrate=1000 key-int-max=30 ! "
    "rtph264pay pt=96 config-interval=1 ! "
    f"udpsink host={IP_PC_GUARDIA} port={PUERTO_UDP}"
)

def abrir_puerta():
    """Subrutina para la activación de la cerradura/puerta."""
    print("[CERRADURA] ¡Acceso concedido! Abriendo puerta...")

# -------------------------------------------------------------------------
# PASO 1: Tomar foto de referencia si no existe
# -------------------------------------------------------------------------
if not os.path.exists("referencia.jpg"):
    print("[FOTO] No se encontró 'referencia.jpg'. Sonríe a la cámara...")
    cap_temp = cv2.VideoCapture(GSTREAMER_FOTO, cv2.CAP_GSTREAMER)
    if not cap_temp.isOpened():
        sys.exit("Error: No se pudo abrir el pipeline para la foto de referencia.")
    
    # Calentamiento: las webcams baratas tardan en ajustar exposición y balance de
    # blancos. Se leen y descartan fotogramas ~4 s y se conserva el último.
    print("[FOTO] Mira a la cámara con buena luz; la foto se toma en ~4 segundos...")
    t0 = time.time()
    ok, frame = False, None
    while time.time() - t0 < 4:
        ok, frame = cap_temp.read()
    if ok and frame is not None:
        cv2.imwrite("referencia.jpg", frame)
        print("[FOTO] ¡Foto de referencia guardada!")
    else:
        sys.exit("[FOTO] Error al tomar la foto de referencia.")
    cap_temp.release()


# -------------------------------------------------------------------------
# PASO 2: Cargar modelos YuNet y SFace
# -------------------------------------------------------------------------
# Los modelos los instala la receta en /usr/share/app-camara. Para pruebas manuales
# con los modelos copiados a otra carpeta: APP_MODELOS=/home/root python3 raspberry-emisor.py
DIR_MODELOS = os.environ.get("APP_MODELOS", "/usr/share/app-camara")
detector = cv2.FaceDetectorYN.create(
    os.path.join(DIR_MODELOS, "face_detection_yunet_2023mar.onnx"), "", (320, 320))
recognizer = cv2.FaceRecognizerSF.create(
    os.path.join(DIR_MODELOS, "face_recognition_sface_2021dec.onnx"), "")
UMBRAL = 0.363

def obtener_embedding(img):
    h, w = img.shape[:2]
    detector.setInputSize((w, h))
    _, caras = detector.detect(img)
    if caras is None or len(caras) == 0:
        return None
    cara_alineada = recognizer.alignCrop(img, caras[0])
    return recognizer.feature(cara_alineada)

ref_img = cv2.imread("referencia.jpg")
ref_embedding = obtener_embedding(ref_img)

if ref_embedding is None:
    sys.exit("[ERROR] No se detectó ninguna cara en 'referencia.jpg'. Borra la imagen y vuelve a intentar.")

print("[SISTEMA] Referencia enrolada correctamente.")

# -------------------------------------------------------------------------
# PASO 3: Ejecución en vivo (Reconocimiento Local + Transmisión por Red)
# -------------------------------------------------------------------------
cap = cv2.VideoCapture(GSTREAMER_PIPELINE, cv2.CAP_GSTREAMER)

if not cap.isOpened():
    sys.exit("[ERROR] No se pudo abrir el pipeline de GStreamer en el emisor. "
             "Revisa la cámara y que existan los plugins (x264enc, rtph264pay).")

print("[SISTEMA] Transmitiendo video y monitoreando acceso...")

while True:
    ok, frame = cap.read()
    if not ok:
        print("[SISTEMA] Error al leer fotograma de GStreamer.")
        break

    h, w = frame.shape[:2]
    detector.setInputSize((w, h))
    _, caras = detector.detect(frame)

    if caras is not None:
        for c in caras:
            cara_alineada = recognizer.alignCrop(frame, c)
            emb = recognizer.feature(cara_alineada)
            score = recognizer.match(ref_embedding, emb, cv2.FaceRecognizerSF_FR_COSINE)
            
            if score >= UMBRAL:
                print(f"[RECONOCIDO] Usuario autenticado (Score: {score:.2f})")
                abrir_puerta()

cap.release()