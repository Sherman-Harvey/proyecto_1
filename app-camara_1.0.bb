import json
import os
import socket
import sys
import time
import cv2

# ---------------------------------------------------------------------------
# Configuracion
# ---------------------------------------------------------------------------
PUERTO_UDP = 5000  # video RTP/H.264 (debe coincidir con pc-receptor-udp.py)
PUERTO_TEL = 5001  # telemetria JSON hacia el receptor (metricas y estado)

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
print(f"[SISTEMA] Receptor de video: {IP_PC_GUARDIA}:{PUERTO_UDP}")
sock_tel = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

# Foto de referencia (persistente entre reinicios) y modelos
DIR_DATOS = os.environ.get("APP_DATOS", "/home/root")
REFERENCIA = os.path.join(DIR_DATOS, "referencia.jpg")
DIR_MODELOS = os.environ.get("APP_MODELOS", "/usr/share/app-camara")

# Variable booleana de estado, tambien publicada como "1"/"0" en este archivo
# para que cualquier otro programa del sistema la pueda leer (cat ...).
ARCHIVO_ESTADO = os.environ.get("APP_ESTADO", "/run/app-camara/reconocido")

# Reconocimiento
UMBRAL = 0.363                  # similitud minima (coseno) con la foto de referencia
FRAMES_PARA_ACTIVAR = 3         # fotogramas seguidos reconocidos -> reconocido=True
SEGUNDOS_PARA_DESACTIVAR = 1.5  # segundos sin reconocerla -> reconocido=False
SCORE_ENROLAR = 0.85            # confianza minima del detector para tomar la referencia
FRAMES_ENROLAR = 8              # fotogramas seguidos con una cara clara antes de tomarla
INTERVALO_METRICAS = int(os.environ.get("APP_METRICAS_S", "5"))  # s entre lineas en consola

# Fuente de video. Por defecto: webcam USB (forzando memoria normal, formato YUY2,
# para evitar el DMABuf que GStreamer 1.28 prefiere por defecto en v4l2src).
# Para pruebas se puede cambiar con la variable de entorno FUENTE.
FUENTE = os.environ.get(
    "FUENTE",
    "v4l2src device=/dev/video0 ! video/x-raw, format=YUY2, width=640, height=480, framerate=30/1",
)

# Pipeline solo para esperar la cara de referencia (sin enviar nada por la red)
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

# ---------------------------------------------------------------------------
# Acciones y utilidades
# ---------------------------------------------------------------------------
def abrir_puerta():
    """Se llama UNA vez cuando se empieza a reconocer al usuario."""
    print("[CERRADURA] ¡Acceso concedido! Abriendo puerta...")
    # TODO: aqui va el control real de la cerradura (por ejemplo, un GPIO)

def cerrar_puerta():
    """Se llama UNA vez cuando se deja de reconocer al usuario."""
    print("[CERRADURA] Usuario ausente. Cerrando puerta...")
    # TODO: aqui va el control real de la cerradura (por ejemplo, un GPIO)

def publicar_estado(valor):
    """Escribe 1/0 en ARCHIVO_ESTADO para que otros programas lo lean."""
    try:
        os.makedirs(os.path.dirname(ARCHIVO_ESTADO), exist_ok=True)
        tmp = ARCHIVO_ESTADO + ".tmp"
        with open(tmp, "w") as f:
            f.write("1\n" if valor else "0\n")
        os.replace(tmp, ARCHIVO_ESTADO)
    except OSError as e:
        print(f"[ESTADO] No se pudo escribir {ARCHIVO_ESTADO}: {e}")

def cambio_estado(valor, score=None):
    """Se llama solo cuando la variable 'reconocido' cambia de valor."""
    publicar_estado(valor)
    if valor:
        print(f"[RECONOCIDO] Usuario autenticado (Score: {score:.2f}) -> reconocido=True")
        abrir_puerta()
    else:
        print("[SIN RECONOCIMIENTO] Ya no se reconoce al usuario -> reconocido=False")
        cerrar_puerta()

def cpu_stat():
    """(tiempo total, tiempo ocioso) de la CPU segun /proc/stat."""
    with open("/proc/stat") as f:
        v = [int(x) for x in f.readline().split()[1:]]
    return sum(v[:8]), v[3] + v[4]

def temp_cpu():
    """Temperatura de la CPU en grados C, o None si no se puede leer."""
    try:
        with open("/sys/class/thermal/thermal_zone0/temp") as f:
            return int(f.read()) / 1000.0
    except (OSError, ValueError):
        return None

# ---------------------------------------------------------------------------
# PASO 1: Cargar modelos YuNet y SFace
# ---------------------------------------------------------------------------
# Umbral de deteccion 0.7 (el valor por defecto de YuNet es 0.9): mas tolerante
# con webcams de baja calidad o poca luz.
detector = cv2.FaceDetectorYN.create(
    os.path.join(DIR_MODELOS, "face_detection_yunet_2023mar.onnx"), "", (320, 320), 0.7)
recognizer = cv2.FaceRecognizerSF.create(
    os.path.join(DIR_MODELOS, "face_recognition_sface_2021dec.onnx"), "")

def obtener_embedding(img):
    h, w = img.shape[:2]
    detector.setInputSize((w, h))
    _, caras = detector.detect(img)
    if caras is None or len(caras) == 0:
        return None
    cara_alineada = recognizer.alignCrop(img, caras[0])
    return recognizer.feature(cara_alineada)

# ---------------------------------------------------------------------------
# PASO 2: Foto de referencia
#   Si no existe, espera SIN LIMITE DE TIEMPO a que aparezca una cara clara
#   frente a la camara y la guarda. (La primera persona que aparezca queda
#   como la persona autorizada.)
# ---------------------------------------------------------------------------
def esperar_cara_y_guardar_referencia():
    cap_temp = cv2.VideoCapture(GSTREAMER_FOTO, cv2.CAP_GSTREAMER)
    if not cap_temp.isOpened():
        sys.exit("[FOTO] Error: No se pudo abrir la camara para la foto de referencia.")
    print("[FOTO] No hay foto de referencia. Esperando a que aparezca una cara frente a la camara...")
    estables = 0
    fallos = 0
    t_aviso = time.time()
    try:
        while True:
            ok, frame = cap_temp.read()
            if not ok or frame is None:
                fallos += 1
                if fallos > 100:
                    sys.exit("[FOTO] Error: la camara no entrega fotogramas.")
                time.sleep(0.05)
                continue
            fallos = 0
            h, w = frame.shape[:2]
            detector.setInputSize((w, h))
            _, caras = detector.detect(frame)
            if caras is not None and len(caras) > 0 and caras[:, -1].max() >= SCORE_ENROLAR:
                estables += 1
            else:
                estables = 0
            if estables >= FRAMES_ENROLAR:
                cv2.imwrite(REFERENCIA, frame)
                print("[FOTO] ¡Foto de referencia guardada!")
                return
            if time.time() - t_aviso >= 10:
                print("[FOTO] Esperando una cara...")
                t_aviso = time.time()
    finally:
        cap_temp.release()

ref_embedding = None
if os.path.exists(REFERENCIA):
    ref_embedding = obtener_embedding(cv2.imread(REFERENCIA))
    if ref_embedding is None:
        print("[FOTO] La referencia guardada no tiene una cara detectable; se descarta.")
        os.remove(REFERENCIA)
if ref_embedding is None:
    esperar_cara_y_guardar_referencia()
    ref_embedding = obtener_embedding(cv2.imread(REFERENCIA))
    if ref_embedding is None:
        sys.exit("[ERROR] No se detectó ninguna cara en la foto de referencia recién guardada.")

print("[SISTEMA] Referencia enrolada correctamente.")

# ---------------------------------------------------------------------------
# PASO 3: Ejecución en vivo (Reconocimiento Local + Transmisión por Red)
#   'reconocido' es la variable booleana de estado: pasa a True cuando se
#   reconoce a la persona de referencia y vuelve a False cuando deja de verse.
# ---------------------------------------------------------------------------
cap = cv2.VideoCapture(GSTREAMER_PIPELINE, cv2.CAP_GSTREAMER)

if not cap.isOpened():
    sys.exit("[ERROR] No se pudo abrir el pipeline de GStreamer en el emisor. "
             "Revisa la cámara y que existan los plugins (x264enc, rtph264pay).")

print("[SISTEMA] Transmitiendo video y monitoreando acceso...")
print(f"[SISTEMA] Telemetria hacia {IP_PC_GUARDIA}:{PUERTO_TEL}")
print("[SISTEMA] Esperando, sin limite de tiempo, a reconocer al usuario...")

reconocido = False
publicar_estado(reconocido)
consecutivos = 0       # fotogramas seguidos en que se reconocio a la persona
t_ultimo_visto = 0.0   # ultima vez que se la reconocio

# Metricas (ventana de 1 s)
frames = 0
acum_ms = 0.0
score_max = -1.0
fps_pi = 0.0
cpu_pct = 0.0
temp = temp_cpu()
t_ref = time.time()
t_print = t_ref
tot0, idle0 = cpu_stat()

try:
    while True:
        ok, frame = cap.read()
        if not ok:
            print("[SISTEMA] Error al leer fotograma de GStreamer.")
            break

        h, w = frame.shape[:2]
        t_ini = time.perf_counter()
        detector.setInputSize((w, h))
        _, caras = detector.detect(frame)

        cajas = []
        score_rec = None
        if caras is not None:
            for c in caras:
                cara_alineada = recognizer.alignCrop(frame, c)
                emb = recognizer.feature(cara_alineada)
                score = float(recognizer.match(ref_embedding, emb, cv2.FaceRecognizerSF_FR_COSINE))
                es_ref = score >= UMBRAL
                score_max = max(score_max, score)
                if es_ref and (score_rec is None or score > score_rec):
                    score_rec = score
                cajas.append([int(c[0]), int(c[1]), int(c[2]), int(c[3]), round(score, 3), es_ref])
        ms = (time.perf_counter() - t_ini) * 1000.0  # tiempo de deteccion + reconocimiento

        # --- Estado booleano con histeresis (evita parpadeos) ---
        ahora = time.time()
        if score_rec is not None:
            consecutivos += 1
            t_ultimo_visto = ahora
            if not reconocido and consecutivos >= FRAMES_PARA_ACTIVAR:
                reconocido = True
                cambio_estado(True, score_rec)
        else:
            consecutivos = 0
            if reconocido and ahora - t_ultimo_visto > SEGUNDOS_PARA_DESACTIVAR:
                reconocido = False
                cambio_estado(False)

        # --- Telemetria hacia el receptor (se superpone sobre el video en la PC) ---
        try:
            sock_tel.sendto(json.dumps({
                "w": w, "h": h, "ms": round(ms, 1), "fps_pi": round(fps_pi, 1),
                "cpu": round(cpu_pct), "temp": temp,
                "reconocido": reconocido, "cajas": cajas,
            }).encode(), (IP_PC_GUARDIA, PUERTO_TEL))
        except OSError:
            pass

        # --- Metricas: se calculan cada segundo; a consola cada INTERVALO_METRICAS ---
        frames += 1
        acum_ms += ms
        if ahora - t_ref >= 1.0:
            fps_pi = frames / (ahora - t_ref)
            ms_medio = acum_ms / frames
            tot, idle = cpu_stat()
            cpu_pct = 100.0 * (1.0 - (idle - idle0) / max(1, tot - tot0))
            tot0, idle0 = tot, idle
            temp = temp_cpu()
            if ahora - t_print >= INTERVALO_METRICAS:
                temp_txt = f"{temp:.0f}C" if temp is not None else "n/d"
                score_txt = f"{score_max:.2f}" if score_max >= 0 else "-"
                print(f"[METRICAS] fps={fps_pi:.1f} inferencia={ms_medio:.0f}ms "
                      f"cpu={cpu_pct:.0f}% temp={temp_txt} score_max={score_txt} "
                      f"reconocido={reconocido}")
                t_print = ahora
            frames, acum_ms, score_max, t_ref = 0, 0.0, -1.0, ahora
except KeyboardInterrupt:
    pass
finally:
    publicar_estado(False)
    cap.release()