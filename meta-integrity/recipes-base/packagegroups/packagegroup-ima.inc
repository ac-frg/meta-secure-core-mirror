LICENSE = "MIT"
LIC_FILES_CHKSUM = "\
    file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302 \
"

S = "${UNPACKDIR}"

ALLOW_EMPTY:${PN} = "1"

RDEPENDS:${PN} = "\
    ima-evm-utils \
"
