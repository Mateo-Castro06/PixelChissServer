"""Certificate lifecycle using the existing lan.py keytool/sign/install primitives."""
import argparse
import contextlib
import io
import json
import os
from pathlib import Path
import uuid

from auto_support import LanError, atomic_write, fingerprint, private_permissions, read_env, read_json, run


def verify(directory, host, image):
    import lan
    private_permissions(directory)
    for name in ('lan.env', 'server.p12', 'server.crt', 'ca.crt'):
        if not (directory / name).is_file():
            raise LanError('Falta ' + name + '; termina la configuración inicial o la firma de la CA.')
    password = read_env(directory / 'lan.env').get('LAN_TLS_PASSWORD')
    if not password:
        raise LanError('Falta LAN_TLS_PASSWORD. No se publicará el Gateway.')
    command = ['docker', 'run', '--rm', '--network', 'none', '--env', 'LAN_TLS_PASSWORD']
    if hasattr(os, 'getuid'):
        command += ['--user', f'{os.getuid()}:{os.getgid()}']
    command += ['--mount', f'type=bind,source={directory},target=/tls,readonly',
                '--mount', f'type=bind,source={Path(__file__).parent.resolve()},target=/tools,readonly',
                '--pull', 'never', '--entrypoint', 'java', image, '/tools/VerifyTls.java', host]
    result = run(command, check=False, env=dict(os.environ, LAN_TLS_PASSWORD=password))
    status = result.stdout.strip()
    if status not in ('OK', 'TLS_IP', 'TLS_EXPIRED', 'TLS_CA_EXPIRED', 'TLS_CHAIN', 'TLS_KEY',
                       'TLS_USAGE', 'TLS_INVALID', 'TLS_NOT_YET_VALID'):
        raise LanError('No se pudo comprobar TLS con la imagen JDK; descárgala antes de continuar.')
    return status


def prepare(directory, host, image, signed_certificate=None):
    import lan
    first_common = not (directory / 'server.crt').exists() and (directory / 'server.csr').is_file() and (directory / 'ca.crt').is_file()
    status = 'FIRST_COMMON' if first_common else verify(directory, host, image)
    if status == 'OK':
        return None
    if status not in ('TLS_IP', 'TLS_EXPIRED', 'FIRST_COMMON'):
        raise LanError('TLS rechazado (' + status + '). Recupera los archivos correctos; no se cambiará la CA.')
    # A single resumable directory, private and on the same filesystem.
    pending = directory / '.renewal'
    if pending.is_symlink():
        raise LanError('La renovación no puede usar un enlace simbólico fuera del directorio privado.')
    pending.mkdir(mode=0o700, exist_ok=True)
    private_permissions(pending)
    metadata = {'host': host, 'ca': fingerprint(directory / 'ca.crt')}
    old = read_json(pending / 'pending.json')
    if old and old != metadata:
        # A second Wi-Fi change can happen before the administrator signs the first CSR.
        archive = directory / '.renewal-history'
        if archive.is_symlink():
            raise LanError('El archivo de solicitudes no puede ser un enlace simbólico.')
        archive.mkdir(mode=0o700, exist_ok=True)
        pending.rename(archive / uuid.uuid4().hex)
        pending.mkdir(mode=0o700)
        old = {}
        print('Se archivó la CSR anterior dentro del directorio privado; se preparará una para la red actual.')
    if not old:
        atomic_write(pending / 'server.p12', (directory / 'server.p12').read_bytes())
        atomic_write(pending / 'lan.env', (directory / 'lan.env').read_bytes())
        lan.keytool(argparse.Namespace(image=image), [(pending, '/tls', False)], '/tls',
                    lan.password_file(directory), ['-certreq', '-alias', 'server', '-file', 'server.csr',
                    '-keystore', 'server.p12', '-storetype', 'PKCS12', '-storepass:env', 'LAN_TLS_PASSWORD'])
        atomic_write(pending / 'pending.json', json.dumps(metadata))
    if signed_certificate:
        public = Path(signed_certificate).resolve()
    elif (directory / 'ca.p12').is_file():
        public = pending / 'approved.crt'
        if not public.exists():
            with contextlib.redirect_stdout(io.StringIO()):
                lan.sign(argparse.Namespace(host=host, csr=str(pending / 'server.csr'), ca_directory=str(directory),
                                            output=str(public), image=image))
    else:
        print('La CA común necesita la firma del administrador; no se cambió lan.env ni el servidor.')
        print('Envía solo esta CSR pública: ' + str(pending / 'server.csr'))
        print('Administrador: lan.py sign --host ' + host + ' --csr RUTA_CSR --ca-directory RUTA_CA --output certificado.crt')
        print('Después repite start --auto --signed-certificate RUTA_CERTIFICADO.crt')
        raise LanError('Firma pendiente de la CA común. Se conserva la CA; no se publicará un certificado incorrecto.')
    # Reset only the staged copy on a failed/previous installation, never production material.
    atomic_write(pending / 'server.p12', (directory / 'server.p12').read_bytes())
    if not first_common:
        # Existing init/install keystores already contain alias ca. Remove only the staged
        # public trust entry before reusing legacy install; keep the server private key.
        lan.keytool(argparse.Namespace(image=image), [(pending, '/tls', False)], '/tls',
                    lan.password_file(directory), ['-delete', '-alias', 'ca', '-keystore', 'server.p12',
                    '-storetype', 'PKCS12', '-storepass:env', 'LAN_TLS_PASSWORD'])
    for name in ('ca.crt', 'server.crt'):
        (pending / name).unlink(missing_ok=True)
    with contextlib.redirect_stdout(io.StringIO()):
        lan.install(argparse.Namespace(directory=str(pending), ca_certificate=str(directory / 'ca.crt'),
                                      server_certificate=str(public), image=image))
    if verify(pending, host, image) != 'OK':
        raise LanError('El certificado firmado no pasó la validación; el material anterior sigue intacto.')
    if fingerprint(pending / 'ca.crt') != fingerprint(directory / 'ca.crt'):
        raise LanError('No se permite cambiar la CA durante una renovación.')
    return pending
