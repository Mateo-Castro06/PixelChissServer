"""Compose orchestration for the current repository; no new stack or data migrations."""
import hashlib
import json
import os
from pathlib import Path
import re
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request

from auto_support import LanError, run

CORE = ('eureka-server', 'auth-service', 'game-service', 'lobby-service', 'api-gateway')


def select_project(rows, base_file, explicit=None):
    def same(row):
        files = row.get('ConfigFiles', '').split(',')
        return any(os.path.normcase(os.path.abspath(p)) == os.path.normcase(str(base_file.resolve())) for p in files)
    matches = [r['Name'] for r in rows if same(r)]
    if explicit:
        if not re.fullmatch(r'[a-z0-9][a-z0-9_-]*', explicit):
            raise LanError('Nombre de proyecto Compose inválido.')
        if matches and explicit not in matches:
            raise LanError('Este repositorio ya usa otro proyecto Compose; no se creará una segunda instancia.')
        return explicit
    if len(matches) > 1:
        raise LanError('Hay varios proyectos para este checkout; elige uno con --project.')
    if matches:
        return matches[0]
    if rows:
        # Other projects exist, but their identity is never inferred from unrelated services.
        print('No se encontró un proyecto para este checkout; se verificará que no existan contenedores PixelChiss ajenos.')
    return 'docker'


class Docker:
    def __init__(self, root, directory, project=None):
        self.root, self.directory = root, directory
        version = run(['docker', 'version', '--format', '{{.Server.Version}}']).stdout.strip()
        if int(version.split('.')[0]) < 28:
            raise LanError('LAN requiere Docker Engine 28 o posterior.')
        compose_version = run(['docker', 'compose', 'version', '--short']).stdout.strip().lstrip('v').split('-')[0]
        try:
            numbers = tuple(int(n) for n in compose_version.split('.')[:3])
        except ValueError:
            raise LanError('No se pudo determinar la versión Docker Compose.') from None
        if numbers < (2, 24, 4):
            raise LanError('Actualiza Docker Compose a 2.24.4 o posterior para reutilizar el override LAN.')
        context = json.loads(run(['docker', 'context', 'inspect']).stdout)[0]
        endpoint = os.environ.get('DOCKER_HOST') or context.get('Endpoints', {}).get('docker', {}).get('Host', '')
        if endpoint and not endpoint.startswith(('unix://', 'npipe://')):
            raise LanError('El Engine debe ejecutarse en este anfitrión; no se admite un contexto Docker remoto para detección LAN local.')
        info = json.loads(run(['docker', 'info', '--format', '{{json .}}']).stdout)
        if any('rootless' in str(x) for x in info.get('SecurityOptions', [])):
            raise LanError('La automatización del firewall requiere Docker Engine con red de bridge administrada; rootless necesita configuración manual.')
        daemon_config = Path('/etc/docker/daemon.json')
        if os.name != 'nt' and daemon_config.is_file():
            settings = json.loads(daemon_config.read_text())
            if settings.get('allow-direct-routing') or settings.get('iptables') is False:
                raise LanError('El daemon permite routing directo o desactiva su firewall. Revisión administrativa necesaria antes de publicar LAN.')
        base = root / 'infrastructure/docker/docker-compose.yml'
        rows = json.loads(run(['docker', 'compose', 'ls', '-a', '--format', 'json']).stdout)
        self.project = select_project(rows, base, project)
        ids = run(['docker', 'ps', '-aq', '--filter', 'label=com.docker.compose.project=' + self.project]).stdout.split()
        current = json.loads(run(['docker', 'inspect'] + ids).stdout) if ids else []
        self.before = {c['Config']['Labels'].get('com.docker.compose.service'): c for c in current}
        for c in current:
            workdir = c['Config']['Labels'].get('com.docker.compose.project.working_dir', '')
            if workdir and os.path.normcase(os.path.abspath(workdir)) != os.path.normcase(str(base.parent.resolve())):
                raise LanError('El proyecto seleccionado pertenece a otro checkout. No se alterarán sus contenedores; conserva su ruta o revisa el nombre.')
        # Fixed container names in the existing Compose must not collide with another project.
        named = run(['docker', 'ps', '-aq', '--filter', 'name=pixelchiss-']).stdout.split()
        if named:
            others = json.loads(run(['docker', 'inspect'] + named).stdout)
            for c in others:
                if c.get('Name', '').lstrip('/') in {'pixelchiss-gateway', 'pixelchiss-auth', 'pixelchiss-game', 'pixelchiss-lobby', 'pixelchiss-eureka'}:
                    if c['Config']['Labels'].get('com.docker.compose.project') != self.project:
                        raise LanError('Ya hay contenedores PixelChiss en otro proyecto. Indica el proyecto correcto; no se creará otro.')
        self.command = ['docker', 'compose', '-p', self.project,
                        '--env-file', str(root / 'infrastructure/docker/.env'), '--env-file', str(directory / 'lan.env'),
                        '-f', str(base), '-f', str(base.with_name('docker-compose.lan.yml'))]
        if 'prometheus' in self.before or 'grafana' in self.before:
            self.command += ['--profile', 'monitoring']
        self.env = dict(os.environ, LAN_TLS_DIRECTORY=str(directory))

    def call(self, args, **kwargs):
        return run(self.command + args, env=self.env, **kwargs)

    def config(self):
        # config includes secrets. Never print it or CalledProcessError.stdout.
        value = json.loads(self.call(['config', '--format', 'json']).stdout)
        validate_config(value, self.directory)
        self.configuration = value
        return value

    def active_games(self):
        try:
            uri = urllib.parse.urlparse(self.configuration['services']['game-service']['environment']['REDIS_URL'])
            if uri.hostname != 'redis' or uri.username or uri.password:
                return None
            database = int(uri.path.strip('/') or '0')
            # Game stores state in a Redis hash, not a string. No write or domain action.
            script = """
            local cursor='0'; local active=0; local unknown=0; local total=0
            repeat
              local batch=redis.call('SCAN',cursor,'MATCH','pixelchiss:game:state:*','COUNT',100)
              cursor=batch[1]
              for _,key in ipairs(batch[2]) do
                total=total+1; if total>5000 then return {-1,1} end
                local read=redis.pcall('HGET',key,'state')
                if type(read)=='string' then
                  local ok,state=pcall(cjson.decode,read)
                  if not ok or not state.status then unknown=unknown+1
                  elseif state.status=='ACTIVE' then active=active+1
                  elseif state.status~='FINISHED' and state.status~='ABANDONED' then unknown=unknown+1 end
                else unknown=unknown+1 end
              end
            until cursor=='0'
            return {active,unknown}
            """
            result = self.call(['exec', '-T', 'redis', 'redis-cli', '-n', str(database), '--json', 'EVAL', script, '0'], check=False)
            if result.returncode:
                return None
            active, unknown = json.loads(result.stdout)
            return None if unknown or active < 0 else active
        except (ValueError, KeyError, LanError):
            return None

    def source_hashes(self):
        hashes = {}
        for service in CORE:
            files = run(['git', '-C', str(self.root), 'ls-files', '-z', '--cached', '--others', '--exclude-standard', '--', service], check=False)
            if files.returncode:
                raise LanError('Se necesita el checkout Git para detectar cambios de imágenes sin reconstruir siempre.')
            digest = hashlib.sha256()
            for name in sorted(files.stdout.split('\0')):
                p = self.root / name
                relative = Path(name).parts[1:] if name else ()
                used = relative and (relative[0] in ('src', 'app') or
                        relative == ('Dockerfile',) or relative == ('.dockerignore',) or
                        relative in (('pom.xml',), ('requirements.txt',), ('main.py',)))
                if used and p.is_file():
                    digest.update(name.encode()); digest.update(p.read_bytes())
            hashes[service] = digest.hexdigest()
        return hashes

    def build_plan(self, previous, rebuild=False):
        hashes = self.source_hashes()
        build = []
        for name in CORE:
            image = self.configuration['services'][name]['image']
            exists = run(['docker', 'image', 'inspect', image], check=False).returncode == 0
            if rebuild or not exists or (previous.get(name) and previous[name] != hashes[name]):
                build.append(name)
        return build, hashes

    def changes(self, builds):
        lines = self.call(['config', '--hash', '*']).stdout.splitlines()
        hashes = dict(line.split(None, 1) for line in lines if len(line.split(None, 1)) == 2)
        changed = []
        for name in self.configuration['services']:
            old = self.before.get(name)
            if not old or not old['State']['Running'] or name in builds:
                changed.append(name)
            elif old['Config']['Labels'].get('com.docker.compose.config-hash') != hashes.get(name):
                changed.append(name)
            else:
                image = run(['docker', 'image', 'inspect', '--format', '{{.Id}}', self.configuration['services'][name]['image']], check=False)
                if image.returncode == 0 and image.stdout.strip() != old['Image']:
                    changed.append(name)
        return changed

    def build_images(self, builds):
        for name in builds:
            print('Construyendo imagen necesaria: ' + name, flush=True)
            self.call(['build', name])

    def start(self, builds, certificate_changed, timeout):
        self.build_images(builds)
        print('Iniciando el proyecto existente y esperando salud...', flush=True)
        self.call(['up', '-d', '--no-build', '--wait', '--wait-timeout', str(timeout)])
        current = self.containers()
        before_gateway = self.before.get('api-gateway', {}).get('Id')
        if certificate_changed and current['api-gateway']['Id'] == before_gateway:
            self.call(['up', '-d', '--no-deps', '--no-build', '--force-recreate', '--wait', '--wait-timeout', str(timeout), 'api-gateway'])
        if any(self.before.get(n, {}).get('Id') and current.get(n, {}).get('Id') != self.before[n]['Id'] for n in ('redis', 'mongodb')):
            self.call(['restart', 'game-service'])
        self.wait_health(timeout)

    def containers(self):
        ids = self.call(['ps', '-aq']).stdout.split()
        data = json.loads(run(['docker', 'inspect'] + ids).stdout) if ids else []
        return {c['Config']['Labels']['com.docker.compose.service']: c for c in data}

    def wait_health(self, timeout):
        deadline = time.monotonic() + timeout
        while True:
            states = self.containers()
            valid = True
            for name in self.configuration['services']:
                c = states.get(name, {})
                state = c.get('State', {})
                if not state.get('Running') or state.get('Health', {}).get('Status', 'healthy') != 'healthy':
                    valid = False
            if valid:
                self.validate_runtime(states)
                return
            if time.monotonic() >= deadline:
                raise LanError('No todos los servicios alcanzaron salud. Revisa status/diagnose; no borres volúmenes.')
            time.sleep(2)

    def wait_discovery(self, timeout):
        ports = self.configuration['services']['eureka-server'].get('ports', [])
        port = next((p['published'] for p in ports if p.get('host_ip') == '127.0.0.1' and p.get('target') == 8761), None)
        if port is None:
            raise LanError('No se encontró la publicación loopback de Eureka para comprobar el registro.')
        deadline = time.monotonic() + timeout
        while True:
            try:
                request = urllib.request.Request(f'http://127.0.0.1:{port}/eureka/apps', headers={'Accept':'application/json'})
                with urllib.request.urlopen(request, timeout=4) as response:
                    apps = json.load(response)['applications']['application']
                names = {a['name'] for a in apps if any(i['status'] == 'UP' for i in a['instance'])}
                if {'API-GATEWAY','AUTH-SERVICE','GAME-SERVICE','LOBBY-SERVICE'} <= names:
                    return
            except (OSError, KeyError, ValueError):
                pass
            if time.monotonic() >= deadline:
                raise LanError('Eureka todavía no registra los cuatro servicios UP. Revisa status/diagnose.')
            time.sleep(2)

    def validate_runtime(self, states):
        allowed_host = self.configuration['services']['api-gateway']['ports']
        external = {(p['host_ip'], str(p['published'])) for p in allowed_host if p['host_ip'] != '127.0.0.1'}
        for name, container in states.items():
            for bindings in (container['NetworkSettings'].get('Ports') or {}).values():
                for binding in bindings or []:
                    if binding['HostIp'] != '127.0.0.1' and not (name == 'api-gateway' and (binding['HostIp'], binding['HostPort']) in external):
                        raise LanError('Publicación inesperada en ' + name + '; revisión manual necesaria.')


def validate_config(config, directory):
    if not set(CORE).issubset(config.get('services', {})):
        raise LanError('Compose no contiene los servicios PixelChiss esperados.')
    for n in config.get('networks', {}).values():
        options = n.get('driver_opts', {})
        if (n.get('driver', 'bridge') != 'bridge' or options.get('com.docker.network.bridge.trusted_host_interfaces')
                or options.get('com.docker.network.bridge.gateway_mode_ipv4', 'nat') != 'nat'):
            raise LanError('La red Compose permite routing directo o tiene un driver no verificable. No se publicará LAN.')
    gateway = config['services']['api-gateway']
    if gateway.get('environment', {}).get('SPRING_PROFILES_ACTIVE') != 'lan':
        raise LanError('El Gateway no tiene el perfil TLS LAN.')
    local_gateway_port(config)
    external = []
    for name, service in config['services'].items():
        for p in service.get('ports', []):
            if p.get('host_ip') != '127.0.0.1':
                if name != 'api-gateway' or p.get('target') != 8080:
                    raise LanError('Compose intenta publicar un puerto interno fuera de loopback: ' + name)
                external.append(p)
    from lan import private_host
    if len(external) != 1:
        raise LanError('Se requiere una única publicación TLS en una IP privada.')
    try:
        private_host(external[0].get('host_ip', ''))
    except ValueError:
        raise LanError('La publicación TLS debe usar una IPv4 privada.') from None
    if not 1024 <= int(external[0]['published']) <= 65535:
        raise LanError('Puerto LAN inválido.')
    mounts = [m for m in gateway.get('volumes', []) if m.get('target', '').startswith('/app/tls')]
    if len(mounts) != 1 or mounts[0].get('target') != '/app/tls/server.p12' or not mounts[0].get('read_only'):
        raise LanError('Gateway debe montar únicamente server.p12 de solo lectura.')
    if Path(mounts[0]['source']).resolve() != (directory / 'server.p12').resolve():
        raise LanError('LAN_TLS_DIRECTORY no coincide con el directorio privado seleccionado.')
    env = config['services']['auth-service']['environment']
    if not env.get('GOOGLE_DESKTOP_CLIENT_ID') or not env['GOOGLE_DESKTOP_CLIENT_ID'].endswith('.apps.googleusercontent.com'):
        raise LanError('Falta GOOGLE_DESKTOP_CLIENT_ID real en .env. No se publicará LAN.')


def local_gateway_port(config):
    ports = [p['published'] for p in config['services']['api-gateway'].get('ports', [])
             if p.get('host_ip') == '127.0.0.1' and p.get('target') == 8082]
    if len(ports) != 1:
        raise LanError('Compose LAN debe conservar un único conector HTTP loopback del Gateway.')
    return int(ports[0])


def gateway_health(host, port, ca, local_port=8080, timeout=60):
    context = ssl.create_default_context(cafile=str(ca))
    deadline = time.monotonic() + timeout
    urls = [(f'https://{host}:{port}/actuator/health', context), (f'http://127.0.0.1:{local_port}/actuator/health', None)]
    while True:
        try:
            for url, tls in urls:
                with urllib.request.urlopen(url, context=tls, timeout=5) as response:
                    if json.load(response).get('status') != 'UP':
                        raise OSError('No saludable')
            return
        except (OSError, ValueError):
            if time.monotonic() >= deadline:
                raise LanError('Gateway no respondió correctamente por HTTPS validado y HTTP local.') from None
            time.sleep(2)
