SUMMARY = "Aplicación de transmisión de video con GStreamer y reconocimiento facial"
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = "file://gstreamer.py \
           file://raspberry-emisor.py \
           file://app-camara-run \
           file://app-camara.init \
           file://face_detection_yunet_2023mar.onnx \
           file://face_recognition_sface_2021dec.onnx"

S = "${UNPACKDIR}"

# Dependencias de ejecución para que el gestor de paquetes sepa qué necesita
RDEPENDS:${PN} += "python3 python3-core gstreamer1.0 gstreamer1.0-python python3-gpiod"

# Autoarranque con SysV (esta imagen no usa systemd)
inherit update-rc.d
INITSCRIPT_NAME = "app-camara"
INITSCRIPT_PARAMS = "defaults 90 10"

do_install() {
    install -d ${D}${bindir}/app-camara
    install -m 0755 ${S}/gstreamer.py ${D}${bindir}/app-camara/
    install -m 0755 ${S}/raspberry-emisor.py ${D}${bindir}/app-camara/
    install -m 0755 ${S}/app-camara-run ${D}${bindir}/app-camara/

    # Modelos de reconocimiento facial
    install -d ${D}${datadir}/app-camara
    install -m 0644 ${S}/face_detection_yunet_2023mar.onnx ${D}${datadir}/app-camara/
    install -m 0644 ${S}/face_recognition_sface_2021dec.onnx ${D}${datadir}/app-camara/

    # Script de arranque (SysV)
    install -d ${D}${sysconfdir}/init.d
    install -m 0755 ${S}/app-camara.init ${D}${sysconfdir}/init.d/app-camara
}

FILES:${PN} += "${datadir}/app-camara"