"""One-command LAN lifecycle, layered on lan.py's existing certificate commands."""
import argparse
import contextlib
import hashlib
import io
import json
from pathlib import Path
import shutil
import sys

import auto_docker
from auto_firewall import Firewall, scope
from auto_network import choose, discover
from auto_support import (LanError, Transaction, atomic_write, confirm, fingerprint, operation_lock,
                          private_permissions, read_env, read_json, run, update_env)
import auto_tls


def network_for(args):
    return choose(discover(), args.host, args.interface, args.subnet)


def identity(root, project):
    return hashlib.sha256((str(root.resolve()) + '\0' + project).encode()).hexdigest()[:16]


def initialize(args, directory, network):
    import lan
    if (directory / 'lan.env').exists():
        return
    if directory.exists() and any(directory.iterdir()):
        raise LanError('El directorio contiene archivos pero falta lan.env. Recupera su configuración privada; no se reemplazarán claves.')
    if args.ca_certificate:
        print('Se preparará una clave de servidor para la CA común indicada; su clave privada no se solicita.')
    else:
        confirm('Primera configuración TLS: crear una CA INDIVIDUAL nueva para este anfitrión. '
                'Si tu equipo usa una CA común, cancela y usa --ca-certificate RUTA_CA_PUBLICA.crt.', 'CREAR CA')
    with contextlib.redirect_stdout(io.StringIO()):
        lan.init(argparse.Namespace(host=network.address, port=str(args.port or 8443), output=str(directory), image=args.image),
                 create_ca=not bool(args.ca_certificate))
    if args.ca_certificate:
        atomic_write(directory / 'ca.crt', Path(args.ca_certificate).read_bytes())
        print('Envía únicamente la CSR pública al administrador: ' + str(directory / 'server.csr'))
        print('Repite start --auto --signed-certificate RUTA_CERTIFICADO.crt cuando recibas su firma.')


def approve_disruption(docker, changes):
    if not changes:
        return False
    active = docker.active_games()
    if active is None or active:
        message = 'No se pudo descartar que existan partidas activas.' if active is None else f'Hay {active} partida(s) activa(s).'
        confirm(message + ' Cambiar/recrear ' + ', '.join(sorted(set(changes))) +
                ' puede cortar WebSocket. Las partidas persistidas se conservarán; los jugadores deberán reconectar a la nueva dirección.')
        return True
    print('Partidas activas: no se encontraron; se comprobará de nuevo antes de recrear servicios.')
    return False


def start(args):
    import lan
    if not 1 <= args.timeout <= 1800:
        raise LanError('--timeout debe estar entre 1 y 1800 segundos.')
    root = lan.ROOT
    directory = Path(args.tls_directory).resolve()
    # Fail before any initialization or publication when mandatory credentials are absent.
    env = read_env(root / 'infrastructure/docker/.env')
    client = env.get('GOOGLE_DESKTOP_CLIENT_ID', '')
    if not client or not client.endswith('.apps.googleusercontent.com'):
        raise LanError('Configura GOOGLE_DESKTOP_CLIENT_ID real en .env. No se inventan credenciales ni se sobrescribe .env.')
    run(['docker', 'version', '--format', '{{.Server.Version}}'])
    network = network_for(args)
    print(f'Red seleccionada: {network.interface}, {network.address}, subred {network.subnet}, perfil {network.profile}.')
    previous = read_json(directory / 'auto-state.json') if directory.exists() else {}
    if network.profile != 'Private' or previous.get('network') != network.to_dict():
        confirm('Vas a usar esta red para recibir jugadores. Su clasificación no garantiza que sea confiable. '
                'Continúa solo en una red autorizada; no se cambiará el perfil del sistema ni se desactivará el firewall.')
    docker = auto_docker.Docker(root, directory, args.project or previous.get('project'))
    if run(['docker', 'image', 'inspect', args.image], check=False).returncode:
        print('Descargando la imagen JDK necesaria para comprobar TLS...')
        run(['docker', 'pull', args.image])
    initialize(args, directory, network)
    private_permissions(directory)
    with operation_lock(directory):
        if (directory / 'transaction.json').exists():
            raise LanError('Hay una actualización interrumpida; ejecuta recover y luego start --auto.')
        values = read_env(directory / 'lan.env')
        port = lan.port(args.port or values.get('LAN_HTTPS_PORT', '8443'))
        docker.config()
        firewall = Firewall(identity(root, docker.project), scope(network, port))
        builds, hashes = docker.build_plan(previous.get('sources', {}), args.rebuild)
        # Older installations may have images but no recorded source baseline. Upgrade
        # this Gateway once so start --auto cannot advertise a resource absent from it.
        if previous.get('caDiscoveryVersion') != 1 and 'api-gateway' not in builds:
            builds.append('api-gateway')
        # Stage certificate first. Common CA requests stop here before changing production configuration.
        pending = auto_tls.prepare(directory, network.address, args.image, args.signed_certificate)
        env_text = (directory / 'lan.env').read_text(encoding='utf-8')
        new_text = update_env(env_text, {'LAN_BIND_ADDRESS': network.address, 'LAN_HTTPS_PORT': port})
        changed_files = new_text != env_text or pending is not None
        state_fields = {'version': 1, 'project': docker.project, 'network': network.to_dict(), 'port': port,
                        'sources': hashes, 'caFingerprint': fingerprint(directory / 'ca.crt'), 'caDiscoveryVersion': 1}
        state_changed = any(previous.get(k) != v for k, v in state_fields.items())
        tx = Transaction(directory)
        begun = False
        docker_attempted = False
        try:
            # Only private, recoverable files change here; services remain untouched until approval.
            if changed_files or state_changed:
                tx.begin(); begun = True
            if new_text != env_text:
                atomic_write(directory / 'lan.env', new_text)
            if pending:
                for name in ('server.p12', 'server.crt'):
                    atomic_write(directory / name, (pending / name).read_bytes())
            config = docker.config()
            published = [p for p in config['services']['api-gateway']['ports'] if p['host_ip'] != '127.0.0.1']
            if published[0]['host_ip'] != network.address or int(published[0]['published']) != port:
                raise LanError('Una variable externa cambia la IP/puerto esperados. Revisa tu entorno; no se publicará otra dirección.')
            changes = docker.changes(builds)
            if pending:
                changes.append('api-gateway')
            disruption = changes + (['cambio de red/firewall'] if previous.get('network') != network.to_dict() else [])
            approved = approve_disruption(docker, disruption)
            firewall.ensure()  # Explicit authorization is independent from network/maintenance approval.
            # Builds can take minutes. Do them before the final game/TLS/firewall checks,
            # so a newly started game is not overlooked while an image is compiling.
            docker.build_images(builds)
            if auto_tls.verify(directory, network.address, args.image) != 'OK':
                raise LanError('TLS no válido antes del arranque; se restaurará la configuración.')
            if not firewall.valid():
                raise LanError('La protección del firewall dejó de estar verificada; no se publicará la configuración.')
            # Detect a newly created game during preparation before any service recreation.
            if not approved:
                approve_disruption(docker, disruption)
            if changes:
                docker_attempted = True
                docker.start([], pending is not None, args.timeout)
            else:
                print('Servicios sin cambios: no se reconstruirán ni recrearán contenedores.')
                docker.wait_health(args.timeout)
            docker.wait_discovery(min(args.timeout, 60))
            auto_docker.gateway_health(network.address, port, directory / 'ca.crt',
                                       auto_docker.local_gateway_port(config), min(args.timeout, 60))
            state = dict(state_fields, firewall={'backend': firewall.backend, 'scope': firewall.spec, 'tag': firewall.tag})
            if state != previous:
                if not begun:
                    tx.begin(); begun = True
                atomic_write(directory / 'auto-state.json', json.dumps(state, indent=2) + '\n')
            undo = firewall.undo_text() + '\n'
            if not (directory / 'firewall-undo.txt').exists() or (directory / 'firewall-undo.txt').read_text() != undo:
                atomic_write(directory / 'firewall-undo.txt', undo)
            if begun:
                tx.finish(); begun = False
        except BaseException:
            if begun:
                tx.restore()
                print('Se restauraron lan.env y el material TLS anterior; los respaldos privados se conservaron.')
                if docker_attempted and docker.before.get('api-gateway', {}).get('State', {}).get('Running'):
                    try:
                        # No volumes removed; only the previously running stack is reconciled.
                        docker.config()
                        docker.start([], changed_files, min(args.timeout, 60))
                        print('Se recuperó la configuración anterior de contenedores.')
                    except (LanError, OSError):
                        print('No fue posible reactivar la dirección anterior. Conserva datos/certificados y ejecuta diagnose.')
                print('Las protecciones de firewall se conservan por seguridad; no se retiraron reglas ajenas.')
                if docker_attempted:
                    print('Si algún servicio no se recuperó, consulta status/diagnose. No se eliminaron contenedores ni volúmenes.')
            raise
        # Cleanup is deliberately outside rollback: successful new protection stays installed on cleanup errors.
        if pending:
            shutil.rmtree(pending)  # Only our staged renewal under the protected TLS directory.
        firewall.retire_previous()
        print('\nPixelChiss LAN preparado. Sistema: ' + ('Windows' if sys.platform == 'win32' else 'Linux'))
        print('Interfaz: ' + network.interface + '; subred: ' + network.subnet)
        print('Certificado: válido; CA conservada. Firewall: verificado. Docker: saludable.')
        print(f'REST Godot: https://{network.address}:{port}')
        print(f'WebSocket Godot: wss://{network.address}:{port}/api/game/ws')
        print(f'Descubrimiento CA: https://{network.address}:{port}/.well-known/pixelchiss/ca')
        print('Acceso local: http://127.0.0.1:' + str(auto_docker.local_gateway_port(config)))
        print('Certificado público: ' + str(directory / 'ca.crt'))
        print('Huella CA (DER SHA256): ' + fingerprint(directory / 'ca.crt'))
        print('Comunica IP, puerto y esta huella COMPLETA a los jugadores por un canal independiente y confiable. '
              'Godot descarga la CA; cada jugador debe comparar la huella antes de confirmarla. No hay que copiar archivos.')
        print('Reversión del firewall: ' + str(directory / 'firewall-undo.txt'))
        print('Listo desde este anfitrión. El login Google real y el acceso desde otro computador requieren sus propias pruebas.')


def inspect_command(args):
    import lan
    directory = Path(args.tls_directory).resolve()
    if args.command == 'network':
        networks = discover()
        if not networks:
            raise LanError('No se detectaron redes físicas IPv4 privadas aptas.')
        for n in networks:
            print(json.dumps(n.to_dict(), ensure_ascii=False))
        return
    if args.command == 'recover':
        private_permissions(directory)
        with operation_lock(directory):
            if not (directory / 'transaction.json').exists():
                print('No hay una transacción pendiente que recuperar.')
                return
            confirm('Restaurar la configuración privada anterior desde su respaldo. No borra partidas ni inicia contenedores.')
            Transaction(directory).restore()
            print('Archivos restaurados. Ejecuta start --auto para validar la red/firewall y reconciliar contenedores.')
        return
    values = read_env(directory / 'lan.env')
    host, port = lan.private_host(args.host or values['LAN_BIND_ADDRESS']), lan.port(args.port or values.get('LAN_HTTPS_PORT', '8443'))
    if args.command == 'url':
        print(f'REST: https://{host}:{port}\nWebSocket: wss://{host}:{port}/api/game/ws')
        print(f'Descubrimiento CA: https://{host}:{port}/.well-known/pixelchiss/ca')
        print('CA pública: ' + str(directory / 'ca.crt') + '\nHuella DER SHA256: ' + fingerprint(directory / 'ca.crt'))
        print('Comparte la huella completa por un canal independiente y confiable; descargar la CA no autentica al anfitrión.')
        return
    if args.command == 'cert-check':
        result = auto_tls.verify(directory, host, args.image)
        print('Certificado: ' + result)
        if result != 'OK':
            raise LanError('El certificado no es apto para esta IP. Ejecuta start --auto para preparar su renovación.')
        return
    previous = read_json(directory / 'auto-state.json')
    docker = auto_docker.Docker(lan.ROOT, directory, args.project or previous.get('project'))
    if args.command == 'stop':
        confirm('Detener únicamente el proyecto ' + docker.project + ' interrumpirá sus conexiones. Se conservan partidas y volúmenes.')
        docker.call(['stop'])
        print('Proyecto detenido sin borrar volúmenes ni datos.')
        return
    docker.config()
    if args.command == 'firewall-check':
        firewall = Firewall(identity(lan.ROOT, docker.project), scope(network_for(args), port))
        firewall.detect()
        if not firewall.valid():
            raise LanError('No se verificó la protección actual; start --auto puede solicitar autorización para corregirla.')
        print('Firewall instalado y verificado, sin modificaciones.')
        return
    print('Proyecto Compose: ' + docker.project)
    print('Configuración: https://' + host + ':' + str(port))
    print(f'Descubrimiento CA: https://{host}:{port}/.well-known/pixelchiss/ca')
    print('Huella CA (DER SHA256): ' + fingerprint(directory / 'ca.crt'))
    print('Estado TLS/firewall: consulta diagnose para verificarlos en vivo; status solo consulta contenedores y configuración.')
    for name, c in docker.containers().items():
        state = c.get('State', {})
        print(name + ': ' + ('ejecutando' if state.get('Running') else 'detenido') + ', ' + state.get('Health', {}).get('Status', 'sin healthcheck'))
    if args.command == 'diagnose':
        failures = []
        def step(label, action):
            try:
                action(); print(label + ': OK')
            except (LanError, OSError, ValueError):
                failures.append(label); print(label + ': pendiente o no válido (sin cambios)')
        networks = discover()
        for n in networks:
            print('Red: ' + json.dumps(n.to_dict(), ensure_ascii=False))
        step('Servicios y puertos', lambda: docker.wait_health(0))
        step('Registro Eureka', lambda: docker.wait_discovery(2))
        step('Certificado', lambda: require_tls(directory, host, args.image))
        def fw_check():
            net = choose(networks, args.host, args.interface, args.subnet, interactive=False)
            fw = Firewall(identity(lan.ROOT, docker.project), scope(net, port)); fw.detect()
            if not fw.valid():
                raise LanError('Firewall no verificado')
        step('Firewall', fw_check)
        step('HTTP local / HTTPS LAN', lambda: auto_docker.gateway_health(host, port, directory / 'ca.crt',
             auto_docker.local_gateway_port(docker.configuration), 2))
        active = docker.active_games()
        print('Partidas activas: ' + ('desconocido; se pedirá confirmación' if active is None else str(active)))
        if failures:
            raise LanError('Diagnóstico pendiente: ' + ', '.join(failures) + '. No se modificaron archivos, reglas ni servicios.')


def require_tls(directory, host, image):
    status = auto_tls.verify(directory, host, image)
    if status != 'OK':
        raise LanError(status)


def add_commands(commands, root):
    for name in ('start', 'network', 'status', 'cert-check', 'firewall-check', 'url', 'diagnose', 'stop', 'recover'):
        parser = commands.add_parser(name, help={'start': 'Preparar e iniciar LAN automáticamente', 'network': 'Consultar redes sin cambios',
            'status': 'Consultar servicios LAN', 'cert-check': 'Verificar TLS sin cambios', 'firewall-check': 'Verificar firewall sin cambios',
            'url': 'Mostrar direcciones para Godot', 'diagnose': 'Diagnóstico sin cambios', 'stop': 'Detener solo este proyecto',
            'recover': 'Restaurar una actualización interrumpida'}[name])
        parser.add_argument('--tls-directory', default=str(root / 'infrastructure/docker/lan-secrets'))
        parser.add_argument('--host', help='Seleccionar una IP presente en una interfaz física')
        parser.add_argument('--interface', help='Seleccionar interfaz entre las detectadas')
        parser.add_argument('--subnet', help='Seleccionar la subred detectada, sin ampliarla')
        parser.add_argument('--port', type=int)
        parser.add_argument('--project', help='Nombre Compose existente; nunca otro proyecto para la misma instalación')
        parser.add_argument('--image', default='maven:3.9-eclipse-temurin-21')
        if name == 'start':
            parser.add_argument('--auto', action='store_true', required=True)
            parser.add_argument('--timeout', type=int, default=180)
            parser.add_argument('--rebuild', action='store_true', help='Reconstruir expresamente las imágenes de los cinco servicios')
            parser.add_argument('--signed-certificate', help='Certificado público firmado por la CA existente')
            parser.add_argument('--ca-certificate', help='CA pública común solo para la primera configuración')
