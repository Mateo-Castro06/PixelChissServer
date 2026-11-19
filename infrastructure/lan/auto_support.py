"""Local automation primitives; no secrets in subprocess errors or public temp files."""
import base64
import contextlib
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import uuid


class LanError(ValueError):
    pass


def run(command, *, check=True, input=None, env=None, interactive=False, encoding='utf-8'):
    try:
        result = subprocess.run(command, input=input, text=True, env=env, encoding=encoding,
                                stdout=None if interactive else subprocess.PIPE,
                                stderr=None if interactive else subprocess.PIPE)
    except OSError:
        raise LanError('No se pudo ejecutar ' + Path(command[0]).name + '. Revisa su instalación.') from None
    if check and result.returncode:
        raise LanError('Falló ' + Path(command[0]).name + '. No se muestran argumentos ni salida privada.')
    return result


def powershell(script, **kwargs):
    prefix = "$ErrorActionPreference='Stop'; [Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false); $OutputEncoding=[Console]::OutputEncoding; "
    encoded = base64.b64encode((prefix + script).encode('utf-16le')).decode()
    result = run(['powershell.exe', '-NoProfile', '-NonInteractive', '-EncodedCommand', encoded], encoding='utf-8', **kwargs)
    if result.stdout:
        result.stdout = result.stdout.lstrip('\ufeff')
    return result


def ps_quote(value):
    return "'" + str(value).replace("'", "''") + "'"


def confirm(message, word='AUTORIZO'):
    print(message)
    if not sys.stdin.isatty():
        raise LanError('Se necesita confirmación interactiva. Ejecuta el comando en tu terminal; no hay aprobación automática.')
    try:
        answer = input('Escribe ' + word + ' para continuar: ').strip()
    except EOFError:
        answer = ''
    if answer != word:
        raise LanError('Operación cancelada; no se autorizaron los cambios.')


def read_env(path):
    values = {}
    if not path.is_file():
        raise LanError('Falta ' + path.name + '. Completa primero la configuración inicial del manual LAN.')
    for line in path.read_text(encoding='utf-8-sig').splitlines():
        match = re.fullmatch(r'\s*([A-Z_][A-Z0-9_]*)\s*=\s*(.*?)\s*', line)
        if not match:
            continue
        value = match[2]
        if value.startswith("'") and value.endswith("'"):
            value = value[1:-1].replace("\\'", "'")
        elif value.startswith('"') and value.endswith('"'):
            value = value[1:-1]
        else:
            value = value.split(' #', 1)[0].strip()
        values[match[1]] = value
    return values


def update_env(text, changes):
    text = text.lstrip('\ufeff')
    remaining = dict(changes)
    lines = []
    for line in text.splitlines():
        match = re.match(r'^\s*([A-Z_][A-Z0-9_]*)\s*=', line)
        if match and match[1] in changes:
            key = match[1]
            if key in remaining:
                lines.append(key + '=' + str(remaining.pop(key)))
            continue  # Remove duplicate assignments only for the keys being updated.
        lines.append(line)
    lines.extend(key + '=' + str(value) for key, value in remaining.items())
    return '\n'.join(lines) + '\n'


def atomic_write(path, data):
    path = Path(path)
    if path.is_symlink():
        raise LanError('No se permite escribir configuración a través de enlaces simbólicos.')
    fd, temporary = tempfile.mkstemp(prefix='.pixelchiss-', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as stream:
            if os.name != 'nt':
                os.fchmod(stream.fileno(), 0o600)
            stream.write(data.encode() if isinstance(data, str) else data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        if os.name != 'nt' and hasattr(os, 'O_DIRECTORY'):
            parent = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(parent)
            finally:
                os.close(parent)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def private_permissions(directory):
    paths = [directory] + [p for p in directory.iterdir() if p.name == 'lan.env' or p.suffix == '.p12']
    if any(p.is_symlink() for p in paths):
        raise LanError('El directorio privado y sus claves no pueden ser enlaces simbólicos.')
    if os.name == 'nt':
        script = '$paths=@(' + ','.join(ps_quote(p) for p in paths) + '); '
        script += "foreach($p in $paths){foreach($r in (Get-Acl -LiteralPath $p).Access){" \
                  "$sid=$r.IdentityReference.Translate([System.Security.Principal.SecurityIdentifier]).Value;" \
                  "if($r.AccessControlType -eq 'Allow' -and $sid -in @('S-1-1-0','S-1-5-11','S-1-5-32-545')){exit 4}}}"
        if powershell(script, check=False).returncode:
            raise LanError('Protege lan-secrets con las propiedades de Seguridad de Windows: sin acceso para Everyone/Users/Authenticated Users.')
    else:
        for path in paths:
            stat = path.stat()
            if stat.st_uid != os.getuid() or stat.st_mode & 0o077:
                raise LanError('Protege el directorio privado (700), lan.env y PKCS12 (600), bajo tu usuario, antes de continuar.')


def read_json(path, default=None):
    if not path.exists():
        return {} if default is None else default
    try:
        return json.loads(path.read_text())
    except (ValueError, OSError):
        raise LanError('Estado local de automatización ilegible; usa diagnose/recover y conserva los respaldos.') from None


class Transaction:
    """Recoverable file transaction. Backups stay under the existing private directory."""
    FILES = ('lan.env', 'server.p12', 'server.crt', 'auto-state.json')

    def __init__(self, directory):
        self.directory = directory
        self.journal = directory / 'transaction.json'
        self.backup = None

    def begin(self):
        if self.journal.exists():
            raise LanError('Hay una actualización interrumpida. Ejecuta recover antes de iniciar.')
        base = self.directory / '.backups'
        if base.is_symlink():
            raise LanError('El directorio de respaldos no puede ser un enlace simbólico.')
        base.mkdir(mode=0o700, exist_ok=True)
        self.backup = base / uuid.uuid4().hex
        self.backup.mkdir(mode=0o700)
        present = []
        for name in self.FILES:
            source = self.directory / name
            if source.exists():
                atomic_write(self.backup / name, source.read_bytes())
                present.append(name)
        atomic_write(self.journal, json.dumps({'backup': self.backup.name, 'present': present}))

    def restore(self):
        info = read_json(self.journal)
        if not re.fullmatch(r'[a-f0-9]{32}', info.get('backup', '')):
            raise LanError('El respaldo de recuperación no es válido; no se restauró ningún archivo.')
        backup = self.directory / '.backups' / info['backup']
        if (self.directory / '.backups').is_symlink() or backup.is_symlink():
            raise LanError('Respaldo no válido.')
        for name in self.FILES:
            target = self.directory / name
            if name in info['present']:
                atomic_write(target, (backup / name).read_bytes())
            elif target.exists():
                target.unlink()
        self.journal.unlink()

    def finish(self):
        self.journal.unlink()


@contextlib.contextmanager
def operation_lock(directory):
    path = directory / 'auto.lock'
    try:
        fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    except FileExistsError:
        raise LanError('Otra operación usa lan-secrets. Si hubo una interrupción, comprueba el PID de auto.lock antes de retirarlo manualmente.') from None
    try:
        with os.fdopen(fd, 'w') as stream:
            stream.write(str(os.getpid()))
        yield
    finally:
        path.unlink(missing_ok=True)


def fingerprint(path):
    import ssl
    return hashlib.sha256(ssl.PEM_cert_to_DER_cert(path.read_text())).hexdigest()
