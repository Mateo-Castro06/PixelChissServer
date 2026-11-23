"""Portable LAN certificate/configuration helper. Python 3 + Docker, no host OpenSSL/Bash.

Never changes .env. The start --auto workflow manages LAN files and services after safety checks.
Legacy init/request/sign/install/firewall/check commands keep their existing behavior.
"""
import argparse
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import secrets
import ssl
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
PRIVATE = tuple(ipaddress.ip_network(n) for n in ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16'))


def private_host(value):
    address = ipaddress.ip_address(value)
    if address.version != 4 or not any(address in net for net in PRIVATE):
        raise ValueError('Use the actual RFC1918 IPv4 address of the Docker host.')
    return str(address)


def port(value):
    number = int(value)
    if not 1024 <= number <= 65535:
        raise ValueError('Port must be between 1024 and 65535.')
    return number


def init(args, create_ca=True):
    host = private_host(args.host)
    tls_port = port(args.port)
    directory = Path(args.output).resolve()
    if directory.exists() and any(directory.iterdir()):
        raise ValueError('Output directory is not empty. Keep existing credentials; use another --output for a new host.')
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    password = secrets.token_urlsafe(32)
    env_file = directory / 'lan.env'
    tls_path = directory.as_posix().replace("'", "\\'")
    from auto_support import atomic_write
    atomic_write(env_file, f"LAN_BIND_ADDRESS={host}\nLAN_HTTPS_PORT={tls_port}\nLAN_TLS_PASSWORD={password}\nLAN_TLS_DIRECTORY='{tls_path}'\n")
    env = dict(os.environ, LAN_TLS_PASSWORD=password)
    base = ['docker', 'run', '--rm', '--network', 'none', '--env', 'LAN_TLS_PASSWORD',
            '--mount', f'type=bind,source={directory},target=/tls', '--workdir', '/tls']
    if hasattr(os, 'getuid'):
        base += ['--user', f'{os.getuid()}:{os.getgid()}']
    base += ['--entrypoint', '/bin/sh', args.image, '-c', 'umask 077; exec keytool "$@"', '--']
    common = ['-storetype', 'PKCS12', '-storepass:env', 'LAN_TLS_PASSWORD']
    san = f'SAN=ip:{host},ip:127.0.0.1,dns:localhost'
    commands = [
        ['-genkeypair', '-alias', 'ca', '-dname', 'CN=PixelChiss LAN CA', '-keyalg', 'RSA', '-keysize', '3072',
         '-validity', '3650', '-ext', 'BC=ca:true,pathlen:0', '-ext', 'KU=keyCertSign,cRLSign', '-keystore', 'ca.p12'] + common,
        ['-genkeypair', '-alias', 'server', '-dname', 'CN=PixelChiss LAN', '-keyalg', 'RSA', '-keysize', '3072',
         '-validity', '365', '-ext', san, '-keystore', 'server.p12'] + common,
        ['-certreq', '-alias', 'server', '-file', 'server.csr', '-keystore', 'server.p12'] + common,
        ['-gencert', '-alias', 'ca', '-infile', 'server.csr', '-outfile', 'server.crt', '-rfc', '-validity', '365',
         '-ext', san, '-ext', 'KU=digitalSignature,keyEncipherment', '-ext', 'EKU=serverAuth', '-keystore', 'ca.p12'] + common,
        ['-exportcert', '-alias', 'ca', '-rfc', '-file', 'ca.crt', '-keystore', 'ca.p12'] + common,
        ['-importcert', '-alias', 'ca', '-file', 'ca.crt', '-noprompt', '-keystore', 'server.p12'] + common,
        ['-importcert', '-alias', 'server', '-file', 'server.crt', '-noprompt', '-keystore', 'server.p12'] + common,
    ]
    if not create_ca:
        commands = commands[1:3]  # New host keeps its private key; only a public CSR leaves it.
    for command in commands:
        subprocess.run(base + command, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
    for item in directory.iterdir():
        item.chmod(0o644 if item.suffix == '.crt' else 0o600)
    if not create_ca:
        print(f'Private server key and public server.csr created in {directory}.')
        print('Send ONLY server.csr and the intended private host IP to the team CA administrator. Finish with install.')
        return
    fingerprint = hashlib.sha256(ssl.PEM_cert_to_DER_cert((directory / 'ca.crt').read_text())).hexdigest()
    print(f'Certificates created in {directory}. Keep ca.p12, server.p12 and lan.env private.')
    print(f'CA certificate DER SHA256 (share via an independent trusted channel): {fingerprint}')
    print(f'Public CA discovery after LAN startup: https://{host}:{tls_port}/.well-known/pixelchiss/ca; players need no manual file copy.')
    print(f'LAN endpoint: https://{host}:{tls_port}; local endpoint remains http://127.0.0.1:8080')
    print('Configure GOOGLE_DESKTOP_CLIENT_ID on the host, apply the scoped firewall rules, then follow docs/MANUAL_SERVIDOR_LAN.md.')


def password_file(directory):
    values = dict(line.split('=',1) for line in (directory / 'lan.env').read_text().splitlines() if '=' in line)
    password = values.get('LAN_TLS_PASSWORD','')
    if not password:
        raise ValueError('Keystore password is missing in the private lan.env.')
    return password


def keytool(args, mounts, directory, password, command):
    base=['docker','run','--rm','--network','none','--env','LAN_TLS_PASSWORD']
    if hasattr(os,'getuid'):
        base += ['--user',f'{os.getuid()}:{os.getgid()}']
    for source,target,readonly in mounts:
        base += ['--mount',f'type=bind,source={source},target={target}'+(',readonly' if readonly else '')]
    base += ['--workdir',directory,'--entrypoint','/bin/sh',args.image,'-c','umask 077; exec keytool "$@"','--']
    subprocess.run(base+command,env=dict(os.environ,LAN_TLS_PASSWORD=password),
                   stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True)


def sign(args):
    host=private_host(args.host)
    ca=Path(args.ca_directory).resolve()
    csr=Path(args.csr).resolve()
    output=Path(args.output).resolve()
    if output.exists():
        raise ValueError('Refusing to overwrite a signed certificate.')
    if not csr.is_file() or not (ca/'ca.p12').is_file():
        raise ValueError('A public CSR and the administrator CA keystore are required.')
    output.parent.mkdir(parents=True,exist_ok=True)
    keytool(args,[(ca,'/ca',True),(csr.parent,'/request',True),(output.parent,'/out',False)],'/out',password_file(ca),[
        '-gencert','-alias','ca','-keystore','/ca/ca.p12','-storetype','PKCS12','-storepass:env','LAN_TLS_PASSWORD',
        '-infile','/request/'+csr.name,'-outfile','/out/'+output.name,'-rfc','-validity','365',
        '-ext',f'SAN=ip:{host},ip:127.0.0.1,dns:localhost','-ext','KU=digitalSignature,keyEncipherment','-ext','EKU=serverAuth'])
    output.chmod(0o644)
    print('Public server certificate signed. Return it and ca.crt; never share ca.p12 or the CA password.')


def install(args):
    directory=Path(args.directory).resolve()
    if not (directory/'server.p12').is_file():
        raise ValueError('Generate this host key/CSR with request first.')
    for source,name in ((Path(args.ca_certificate),'ca.crt'),(Path(args.server_certificate),'server.crt')):
        if (directory/name).exists():
            raise ValueError('Refusing to overwrite existing certificates; keep backups before rotating.')
        ssl.PEM_cert_to_DER_cert(source.read_text())
    for source,name in ((Path(args.ca_certificate),'ca.crt'),(Path(args.server_certificate),'server.crt')):
        (directory/name).write_bytes(source.read_bytes())
        (directory/name).chmod(0o644)
    for alias,name in (('ca','ca.crt'),('server','server.crt')):
        keytool(args,[(directory,'/tls',False)],'/tls',password_file(directory),[
            '-importcert','-alias',alias,'-file',name,'-noprompt','-keystore','server.p12',
            '-storetype','PKCS12','-storepass:env','LAN_TLS_PASSWORD'])
    fingerprint=hashlib.sha256(ssl.PEM_cert_to_DER_cert((directory/'ca.crt').read_text())).hexdigest()
    print('Signed certificate installed into the existing host key. Team CA certificate DER SHA256: '+fingerprint)


def firewall(args):
    host = private_host(args.host)
    tls_port = port(args.port)
    network = ipaddress.ip_network(args.subnet, strict=True)
    if network.version != 4 or not any(network.subnet_of(n) for n in PRIVATE) or ipaddress.ip_address(host) not in network:
        raise ValueError('Subnet must be RFC1918 and contain the host. Do not use an Internet-wide rule.')
    if args.platform == 'windows':
        outside = ','.join("'" + str(n) + "'" for n in ipaddress.ip_network('0.0.0.0/0').address_exclude(network))
        suffix = f'-Protocol TCP -LocalAddress {host} -LocalPort {tls_port} -Direction Inbound'
        print('# Run in administrator PowerShell; inspect existing rules first. Nothing has been applied.')
        print(f"New-NetFirewallRule -Name PixelChiss-LAN-Allow-{tls_port} -DisplayName 'PixelChiss LAN' {suffix} -RemoteAddress {network} -Profile Private -Action Allow")
        print(f"New-NetFirewallRule -Name PixelChiss-LAN-Block-{tls_port} -DisplayName 'PixelChiss outside LAN' {suffix} -RemoteAddress {outside} -Profile Any -Action Block")
        print(f"New-NetFirewallRule -Name PixelChiss-LAN-ProfileBlock-{tls_port} -DisplayName 'PixelChiss non-private network' {suffix} -RemoteAddress Any -Profile Public,Domain -Action Block")
        print(f'# Undo: Remove-NetFirewallRule -Name PixelChiss-LAN-Allow-{tls_port},PixelChiss-LAN-Block-{tls_port},PixelChiss-LAN-ProfileBlock-{tls_port}')
    else:
        rule = f'-p tcp -m conntrack --ctorigdst {host} --ctorigdstport {tls_port} ! -s {network} -j DROP'
        print('# Docker Engine with iptables backend: UFW alone does not filter published Docker ports.')
        print('# Nothing has been applied. Check DOCKER-USER exists; do not disable Docker firewall management.')
        print(f'sudo iptables -I DOCKER-USER 1 {rule}')
        print(f'# Undo: sudo iptables -D DOCKER-USER {rule}')
        print('# Persist/reapply according to your distribution; see docs/MANUAL_SERVIDOR_LAN.md.')


def check(args):
    directory = Path(args.tls_directory).resolve()
    if args.lan and not (directory / 'lan.env').is_file():
        raise ValueError('Generate/install LAN certificates first; the private lan.env is missing.')
    if args.lan:
        version = subprocess.run(['docker','version','--format','{{.Server.Version}}'], capture_output=True, text=True, check=True).stdout.strip()
        if int(version.split('.')[0]) < 28:
            raise ValueError('LAN requires Docker Engine >=28 for reliable loopback port isolation. Update Engine/Desktop first.')
    compose = ['docker', 'compose', '--env-file', str(ROOT / 'infrastructure/docker/.env')]
    if args.lan:
        compose += ['--env-file', str(directory / 'lan.env')]
    compose += ['-f', str(ROOT / 'infrastructure/docker/docker-compose.yml')]
    if args.lan:
        compose += ['-f', str(ROOT / 'infrastructure/docker/docker-compose.lan.yml')]
    result = subprocess.run(compose + ['config', '--format', 'json'], env=dict(os.environ,LAN_TLS_DIRECTORY=str(directory)), capture_output=True, text=True, check=True)
    config = json.loads(result.stdout)  # Contains secrets; never print this object.
    for name, service in config['services'].items():
        for published in service.get('ports', []):
            if args.lan and name == 'api-gateway' and published['target'] == 8080:
                private_host(published['host_ip'])
            elif published.get('host_ip') != '127.0.0.1':
                raise ValueError(f'{name}: non-loopback publication is not allowed here.')
    if args.lan:
        gateway = config['services']['api-gateway']
        if gateway['environment'].get('SPRING_PROFILES_ACTIVE') != 'lan' or not gateway['environment'].get('LAN_TLS_PASSWORD'):
            raise ValueError('LAN requires the TLS profile and keystore password.')
        tls_mounts = [v for v in gateway.get('volumes', []) if v.get('target','').startswith('/app/tls')]
        if len(tls_mounts) != 1 or tls_mounts[0].get('target') != '/app/tls/server.p12' or not tls_mounts[0].get('read_only'):
            raise ValueError('Gateway must mount only the server keystore, read-only; never the CA private key directory.')
        for name in ('server.p12', 'ca.crt'):
            if not (directory / name).is_file():
                raise ValueError('Generate the TLS files before starting LAN mode.')
    print('Compose checked: only the TLS Gateway may be published outside loopback. No secrets printed.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    create = commands.add_parser('init', help='Generate a new per-host CA/PKCS12 and lan.env; never overwrite.')
    create.add_argument('--host', required=True)
    create.add_argument('--port', default='8443')
    create.add_argument('--output', default=str(ROOT / 'infrastructure/docker/lan-secrets'))
    create.add_argument('--image', default='maven:3.9-eclipse-temurin-21', help='JDK image containing keytool; pull it before running.')
    request = commands.add_parser('request', help='Generate only a host key/CSR for the existing team CA.')
    request.add_argument('--host', required=True)
    request.add_argument('--port', default='8443')
    request.add_argument('--output', default=str(ROOT / 'infrastructure/docker/lan-secrets'))
    request.add_argument('--image', default='maven:3.9-eclipse-temurin-21')
    signer = commands.add_parser('sign', help='Administrator signs a public CSR without sharing the CA private key.')
    signer.add_argument('--host', required=True)
    signer.add_argument('--csr', required=True)
    signer.add_argument('--ca-directory', default=str(ROOT / 'infrastructure/docker/lan-secrets'))
    signer.add_argument('--output', required=True)
    signer.add_argument('--image', default='maven:3.9-eclipse-temurin-21')
    finish = commands.add_parser('install', help='Install the public signed certificate and CA into this host keystore.')
    finish.add_argument('--directory', default=str(ROOT / 'infrastructure/docker/lan-secrets'))
    finish.add_argument('--ca-certificate', required=True)
    finish.add_argument('--server-certificate', required=True)
    finish.add_argument('--image', default='maven:3.9-eclipse-temurin-21')
    rules = commands.add_parser('firewall' , help='Print scoped and reversible rules, without applying them.')
    rules.add_argument('--host', required=True)
    rules.add_argument('--subnet', required=True)
    rules.add_argument('--port', default='8443')
    rules.add_argument('--platform', choices=('windows','linux'), required=True)
    verify = commands.add_parser('check', help='Validate resolved Compose publications without printing credentials.')
    verify.add_argument('--lan', action='store_true')
    verify.add_argument('--tls-directory', default=str(ROOT / 'infrastructure/docker/lan-secrets'))
    from auto_start import add_commands, inspect_command, start
    add_commands(commands, ROOT)
    args = parser.parse_args()
    try:
        legacy = {'init': init, 'request': lambda a: init(a,False), 'sign': sign, 'install': install, 'firewall': firewall, 'check': check}
        if args.command in legacy:
            legacy[args.command](args)
        elif args.command == 'start':
            start(args)
        else:
            inspect_command(args)
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        # subprocess errors may contain command output. No credential-bearing output is logged.
        print(str(error) if isinstance(error, ValueError) else 'Docker/file operation failed. Existing files were preserved; check prerequisites.', file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print('Operación interrumpida. Conserva los respaldos y consulta status/diagnose.', file=sys.stderr)
        return 130
    return 0


if __name__ == '__main__':
    sys.exit(main())
