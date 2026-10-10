SUMMARY = "Imagen del taller de sistemas embebidos"
LICENSE = "MIT"

require recipes-core/images/core-image-minimal.bb

IMAGE_FEATURES += "ssh-server-openssh allow-empty-password empty-root-password allow-root-login serial-autologin-root"

IMAGE_INSTALL:append = " nano htop python3 python3-pip wpa-supplicant iw kernel-modules linux-firmware-rpidistro-bcm43455 wireless-regdb-static python3-modules gstreamer1.0 gstreamer1.0-plugins-good gstreamer1.0-plugins-bad gstreamer1.0-python opencv app-camara i2c-tools gstreamer1.0-plugins-ugly v4l-utils usbutils"





# Espacio libre extra en el rootfs, en KB
IMAGE_ROOTFS_EXTRA_SPACE = "65536"


FILESEXTRAPATHS:prepend := "${THISDIR}/files:"
SRC_URI += "file://wpa_supplicant.conf"

configure_wifi() {
    cat >> ${IMAGE_ROOTFS}${sysconfdir}/network/interfaces <<EOF

auto wlan0
iface wlan0 inet dhcp
    wpa-conf /etc/wpa_supplicant.conf
    post-up iw dev wlan0 set power_save off
EOF
    install -m 0600 ${THISDIR}/files/wpa_supplicant.conf ${IMAGE_ROOTFS}${sysconfdir}/wpa_supplicant.conf
}
ROOTFS_POSTPROCESS_COMMAND += "configure_wifi; "