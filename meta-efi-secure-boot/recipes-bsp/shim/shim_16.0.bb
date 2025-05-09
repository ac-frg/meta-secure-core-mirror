SUMMARY = "shim is a trivial EFI application."
DESCRIPTION = "shim is a trivial EFI application that, when run, \
attempts to open and execute another application. It will initially \
attempt to do this via the standard EFI LoadImage() and StartImage() \
calls. If these fail (because secure boot is enabled and the binary \
is not signed with an appropriate key, for instance) it will then \
validate the binary against a built-in certificate. If this succeeds \
and if the binary or signing key are not blacklisted then shim will \
relocate and execute the binary."
HOMEPAGE = "https://github.com/rhboot/shim.git"
SECTION = "bootloaders"

LICENSE = "BSD-2-Clause"
LIC_FILES_CHKSUM = "file://COPYRIGHT;md5=b92e63892681ee4e8d27e7a7e87ef2bc"

DEPENDS = "openssl util-linux-native openssl-native sbsigntool-native"

SRC_URI = "https://github.com/rhboot/shim/releases/download/${PV}/shim-${PV}.tar.bz2"

SRC_URI[sha256sum] = "d503f778dc75895d3130da07e2ff23d2393862f95b6cd3d24b10cbd4af847217"

SRC_URI:append:x86-64 = "${@bb.utils.contains('DISTRO_FEATURES', 'msft', \
                         'file://shim' + d.expand('${EFI_ARCH}') + '.efi.signed file://LICENSE' \
                         if uks_signing_model(d) == 'sample' else '', '', d)} \
                        "

UPSTREAM_CHECK_URI = "https://github.com/rhboot/${BPN}/releases"
UPSTREAM_CHECK_REGEX = "releases/tag/v?(?P<pver>\d+(\.\d+)+)"

inherit deploy user-key-store

SHIM_DEFAULT_LOADER = "${@'DEFAULT_LOADER=\\\\\\\\\\\\\\\\SELoader${EFI_ARCH}.efi' if d.getVar('UEFI_SB') == '1' and d.getVar('UEFI_SELOADER') == '1' else ''}"

EXTRA_OEMAKE = "\
    CROSS_COMPILE="${TARGET_PREFIX}" \
    LIB_GCC="`${CC} -print-libgcc-file-name`" \
    ${SHIM_DEFAULT_LOADER} \
    OPENSSL=${STAGING_BINDIR_NATIVE}/openssl \
    HEXDUMP=${STAGING_BINDIR_NATIVE}/hexdump \
    PK12UTIL=${STAGING_BINDIR_NATIVE}/pk12util \
    CERTUTIL=${STAGING_BINDIR_NATIVE}/certutil \
    SBSIGN=${STAGING_BINDIR_NATIVE}/sbsign \
    ENABLE_SBSIGN=1 \
    ${@'VENDOR_CERT_FILE=${WORKDIR}/vendor_cert.cer' \
       if d.getVar('MOK_SB') == '1' else ''} \
    ${@'VENDOR_DBX_FILE=${WORKDIR}/vendor_dbx.esl' \
       if d.getVar('MOK_SB') == '1' and uks_signing_model(d) == 'user' else ''} \
"

PARALLEL_MAKE = ""
COMPATIBLE_HOST = '(i.86|x86_64).*-linux'

EFI_TARGET = "/boot/efi/EFI/BOOT"

MSFT = "${@bb.utils.contains('DISTRO_FEATURES', 'msft', '1', '0', d)}"

EFI_ARCH:x86 = "ia32"
EFI_ARCH:x86-64 = "x64"

# Prepare the signing certificate and keys
python do_prepare_signing_keys() {
    # For UEFI_SB, shim is not built
    if d.getVar('MOK_SB') != '1':
        return

    path = create_mok_vendor_dbx(d)

    # Prepare shim_cert and vendor_cert.
    dir = mok_sb_keys_dir(d)

    import shutil

    shutil.copyfile(dir + 'shim_cert.crt', d.getVar('S') + '/shim.pem')
    pem2der(dir + 'vendor_cert.crt', d.getVar('WORKDIR') + '/vendor_cert.cer', d)

    # Replace the shim certificate with EV certificate for speeding up
    # the progress of MSFT signing.
    if d.expand('${MSFT}') == "1" and uks_signing_model(d) == "sample":
        shutil.copyfile(d.expand('${EV_CERT}'), d.expand('${S}/shim.pem'))
}
addtask prepare_signing_keys after do_configure before do_compile
do_prepare_signing_keys[prefuncs] += "check_deploy_keys"

python do_sign() {
    # The pre-signed shim binary will override the one built from the
    # scratch.
    pre_signed = d.expand('${UNPACKDIR}/shim${EFI_ARCH}.efi.signed')
    dst = d.expand('${B}/shim${EFI_ARCH}.efi.signed')
    if d.expand('${MSFT}') == "1" and os.path.exists(pre_signed):
        import shutil
        shutil.copyfile(pre_signed, dst)
    else:
        if uks_signing_model(d) in ('sample', 'user'):
            uefi_sb_sign(d.expand('${S}/shim${EFI_ARCH}.efi'), dst, d)

    sb_sign(d.expand('${S}/mm${EFI_ARCH}.efi'), d.expand('${B}/mm${EFI_ARCH}.efi.signed'), d)
}
addtask sign after do_compile before do_install

do_install() {
    install -d "${D}${EFI_TARGET}"

    local shim_dst="${D}${EFI_TARGET}/boot${EFI_ARCH}.efi"
    local mm_dst="${D}${EFI_TARGET}/mm${EFI_ARCH}.efi"
    if [ x"${UEFI_SB}" = x"1" -a x"${MOK_SB}" = x"1" ]; then
        install -m 0600 "${B}/shim${EFI_ARCH}.efi.signed" "$shim_dst"
        install -m 0600 "${B}/mm${EFI_ARCH}.efi.signed" "$mm_dst"
    else
        install -m 0600 "${B}/shim${EFI_ARCH}.efi" "${D}${EFI_TARGET}/shim${EFI_ARCH}.efi"
        install -m 0600 "${B}/mm${EFI_ARCH}.efi" "$mm_dst"
    fi
}

# Install the unsigned images for manual signing
do_deploy() {
    install -d ${DEPLOYDIR}/efi-unsigned

    install -m 0600 "${B}/shim${EFI_ARCH}.efi" \
        "${DEPLOYDIR}/efi-unsigned/boot${EFI_ARCH}.efi"
    install -m 0600 "${B}/mm${EFI_ARCH}.efi" \
        "${DEPLOYDIR}/efi-unsigned/mm${EFI_ARCH}.efi"

    if [ x"${UEFI_SB}" = x"1" -a x"${MOK_SB}" = x"1" ]; then
        install -m 0600 "${D}${EFI_TARGET}/boot${EFI_ARCH}.efi" "${DEPLOYDIR}"
    else
        install -m 0600 "${D}${EFI_TARGET}/shim${EFI_ARCH}.efi" "${DEPLOYDIR}"
    fi
    install -m 0600 "${D}${EFI_TARGET}/mm${EFI_ARCH}.efi" "${DEPLOYDIR}"
}
addtask deploy after do_install before do_build

FILES:${PN} = "${EFI_TARGET}"
