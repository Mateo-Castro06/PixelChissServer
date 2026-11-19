"""Host network discovery. Docker/VPN interfaces never become implicit candidates."""
from dataclasses import asdict, dataclass
import ipaddress
import json
from pathlib import Path
import re
import sys

from auto_support import LanError, powershell, run

PRIVATE = [ipaddress.ip_network(n) for n in ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16')]


@dataclass(frozen=True)
class Network:
    interface: str
    address: str
    subnet: str
    gateway: str = ''
    profile: str = 'Unknown'
    default: bool = False

    def to_dict(self):
        return asdict(self)


def candidate(interface, address, prefix, **kwargs):
    parsed = ipaddress.ip_interface(str(address) + '/' + str(prefix))
    if parsed.version != 4 or not any(parsed.network.subnet_of(net) for net in PRIVATE):
        return None
    if parsed.network.prefixlen > 30 or parsed.ip in (parsed.network.network_address, parsed.network.broadcast_address):
        return None
    return Network(interface, str(parsed.ip), str(parsed.network), **kwargs)


def linux_candidates(addresses, routes, physical=None):
    result = []
    for row in addresses:
        name = row['ifname']
        if 'UP' not in row.get('flags', []) or 'LOOPBACK' in row.get('flags', []):
            continue
        if row.get('linkinfo', {}).get('info_kind') or re.match(r'^(docker|br-|veth|virbr|tun|tap|wg|tailscale|zt)', name):
            continue
        if physical is not None and not physical(name):
            continue
        route = next((r for r in routes if r.get('dev') == name and r.get('dst') == 'default'), {})
        for addr in row.get('addr_info', []):
            if addr.get('family') != 'inet' or addr.get('scope') != 'global' or 'tentative' in addr.get('flags', []):
                continue
            net = candidate(name, addr['local'], addr['prefixlen'], gateway=route.get('gateway', ''), default=bool(route))
            if net:
                result.append(net)
    return result


def windows_candidates(rows):
    result = []
    for row in rows:
        if not row.get('physical') or row.get('status') != 'Up':
            continue
        net = candidate(row['interface'], row['address'], row['prefix'], gateway=row.get('gateway', ''),
                        profile=row.get('profile') or 'Unknown', default=bool(row.get('gateway')))
        if net:
            result.append(net)
    return result


def discover():
    if sys.platform == 'win32':
        script = """
        $result=@(); foreach($a in (Get-NetAdapter -Physical | Where-Object Status -eq 'Up')) {
          $p=Get-NetConnectionProfile -InterfaceIndex $a.ifIndex -ErrorAction SilentlyContinue;
          $g=Get-NetRoute -AddressFamily IPv4 -InterfaceIndex $a.ifIndex -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue | Select-Object -First 1;
          foreach($ip in (Get-NetIPAddress -AddressFamily IPv4 -InterfaceIndex $a.ifIndex | Where-Object AddressState -eq 'Preferred')) {
            $result+=@{interface=$a.Name; physical=$true; status=[string]$a.Status; address=$ip.IPAddress;
              prefix=$ip.PrefixLength; gateway=[string]$g.NextHop; profile=[string]$p.NetworkCategory}
          }
        }; ConvertTo-Json -InputObject @($result) -Compress
        """
        return windows_candidates(json.loads(powershell(script).stdout))
    if sys.platform.startswith('linux'):
        addresses = json.loads(run(['ip', '-j', '-4', 'address', 'show']).stdout)
        routes = json.loads(run(['ip', '-j', '-4', 'route', 'show', 'default']).stdout)
        return linux_candidates(addresses, routes, lambda n: (Path('/sys/class/net') / n / 'device').exists())
    raise LanError('Inicio automático soportado en Linux y Windows; este sistema requiere configuración manual.')


def choose(networks, host=None, interface=None, subnet=None, input_fn=input, interactive=None):
    choices = [n for n in networks if (not host or n.address == host) and (not interface or n.interface == interface)]
    if subnet:
        parsed = str(ipaddress.ip_network(subnet, strict=True))
        choices = [n for n in choices if n.subnet == parsed]
    if not choices:
        raise LanError('No hay una interfaz física IPv4 privada apta. Conecta tu equipo a la LAN y ejecuta network.')
    if len(choices) == 1:
        return choices[0]
    # Multiple physical networks remain ambiguous even when one has a default route.
    for i, net in enumerate(choices, 1):
        print(f'{i}. {net.interface}: {net.address} / {net.subnet} ({net.profile})')
    if interactive is False or (interactive is None and not sys.stdin.isatty()):
        raise LanError('Varias redes: indica --interface y/o --host de la lista. No se seleccionó una arbitrariamente.')
    try:
        number = int(input_fn('Elige la red para los jugadores: '))
        if not 1 <= number <= len(choices):
            raise ValueError()
        return choices[number - 1]
    except (ValueError, EOFError):
        raise LanError('Selección de red inválida; no se cambió la configuración.') from None
