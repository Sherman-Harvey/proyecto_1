import gi
gi.require_version('Gst', '1.0')
gi.require_version('GLib', '2.0')
from gi.repository import Gst, GLib

# Inicializar GStreamer
Gst.init(None)

def _ejecutar_pipeline(pipeline_str: str):
    """Subrutina auxiliar para construir y reproducir cualquier pipeline."""
    pipeline = Gst.parse_launch(pipeline_str)
    pipeline.set_state(Gst.State.PLAYING)
    
    loop = GLib.MainLoop()
    print("Ejecutando pipeline. Presiona Ctrl+C para detener...")
    try:
        loop.run()
    except KeyboardInterrupt:
        print("\nDeteniendo pipeline...")
    finally:
        pipeline.set_state(Gst.State.NULL)


def reproducir_webcam(device: str = "/dev/video0"):
    pipeline_str = (
        f"v4l2src device={device} ! "
        "video/x-raw, width=1280, height=720, framerate=10/1 ! "
        "videoconvert ! autovideosink"
    )
    _ejecutar_pipeline(pipeline_str)


# 2. Subrutina para Captura de Pantalla
def reproducir_pantalla():
    pipeline_str = "ximagesrc ! videoconvert ! autovideosink"
    _ejecutar_pipeline(pipeline_str)


# 3. Subrutina para Transmisión RTSP
def reproducir_rtsp(url: str):
    pipeline_str = f"rtspsrc location={url} ! decodebin ! videoconvert ! autovideosink"
    _ejecutar_pipeline(pipeline_str)


# --- Ejemplo de uso ---
if __name__ == "__main__":
    # Descomenta la subrutina que quieras probar:
    
    reproducir_webcam()
    # reproducir_pantalla()
    # reproducir_rtsp("rtsp://wowzaec2demo.streamlock.net/vod/mp4:BigBuckBunny_175k.mov")
    