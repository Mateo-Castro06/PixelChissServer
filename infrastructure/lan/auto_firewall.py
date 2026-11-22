"""Scoped firewall adapters. Tests replace the command runner; no host firewall in tests."""
import hashlib
import ipaddress
import json
import os
import shlex
import sys

from auto_support import LanError, confirm, powershell, ps_quote, run


def ipv4_ranges(values):
    """Normalize Windows CIDR/dotted-mask/range output without accepting broader scope."""
    ranges = []
    for value in values:
        if value == 'Any':
            ranges.append((0, 2**32 - 1))
        elif '-' in value:
            first, last = (ipaddress.IPv4Address(v) for v in value.split('-', 1))
            if int(first) > int(last):
                raise ValueError('Rango inválido')
            ranges.append((int(first), int(last)))
        else:
            network = ipaddress.IPv4Network(value, strict=False)
            ranges.append((int(network.network_address), int(network.broadcast_address)))
    merged = []
    for first, last in sorted(ranges):
        if merged and first <= merged[-1][1] + 1:
            merged[-1] = (merged[-1][0], max(last, merged[-1][1]))
        else:
            merged.append((first, last))
    return merged


def scope(network, port):
    host = ipaddress.ip_address(network.address)
    subnet = ipaddress.ip_network(network.subnet, strict=True)
    from auto_network import PRIVATE
    if host.version != 4 or host not in subnet or not any(subnet.subnet_of(n) for n in PRIVATE) or not 1024 <= int(port) <= 65535:
        raise LanError('Ámbito de firewall inválido.')
    profile = {'Private': 'Private', 'Public': 'Public', 'DomainAuthenticated': 'Domain'}.get(network.profile)
    if sys.platform == 'win32' and profile is None:
        raise LanError('Windows no pudo identificar el perfil de red; no se abrirá el Gateway.')
    return {'host': str(host), 'subnet': str(subnet), 'port': int(port), 'profile': profile or 'Private'}


class Firewall:
    def __init__(self, identity, spec):
        self.identity = identity
        self.spec = spec
        self.prefix = 'pixelchiss-auto:' + identity + ':'
        digest = hashlib.sha256(json.dumps(spec, sort_keys=True).encode()).hexdigest()[:12]
        self.tag = self.prefix + digest
        self.table = 'pixelchiss_' + identity
        self.backend = None
        self.authorized = False
        self.legacy = False

    def consent(self):
        if not self.authorized:
            s = self.spec
            confirm(f"Autorización de firewall: verificar/proteger solo {s['host']}:{s['port']} para {s['subnet']}; "
                    'retirar después únicamente reglas antiguas etiquetadas por esta instalación. No se tocarán reglas ajenas.')
            self.authorized = True

    def privileged(self, args, *, check=True, input=None):
        prefix = [] if os.geteuid() == 0 else ['sudo', '-n']
        return run(prefix + args, check=check, input=input)

    def detect(self, allow_prompt=False):
        if sys.platform == 'win32':
            self.backend = 'windows'
            return
        if not sys.platform.startswith('linux'):
            raise LanError('Firewall automático no disponible en este sistema.')
        # Distinguish lack of privileges from lack of a Docker chain.
        if os.geteuid() != 0 and run(['sudo', '-n', 'true'], check=False).returncode:
            if not allow_prompt:
                raise LanError('Se requiere sudo para comprobar el firewall. Ejecuta start --auto en una terminal; no se modificó nada.')
            self.consent()
            run(['sudo', '-v'], interactive=True)
        nft = self.privileged(['nft', '-j', 'list', 'tables'], check=False) if self.available('nft') else None
        native = False
        if nft and nft.returncode == 0:
            native = any(x.get('table', {}).get('name') == 'docker-bridges'
                         for x in json.loads(nft.stdout).get('nftables', []))
        if native:
            self.backend = 'nftables'
            return
        if self.available('iptables'):
            forward = self.privileged(['iptables', '-S', 'FORWARD'], check=False)
            user = self.privileged(['iptables', '-S', 'DOCKER-USER'], check=False)
            jumps = [line for line in forward.stdout.splitlines() if line.startswith('-A ')]
            if forward.returncode == user.returncode == 0 and jumps and jumps[0] == '-A FORWARD -j DOCKER-USER':
                self.backend = 'iptables'
                return
        raise LanError('No se verificó DOCKER-USER al inicio de FORWARD ni el backend nftables Docker. '
                       'No se publicará LAN. Revisa el backend Docker y ejecuta firewall-check con privilegios; no desactives el firewall.')

    @staticmethod
    def available(name):
        import shutil
        return shutil.which(name) is not None

    def ipt_rule(self, tagged=True):
        s = self.spec
        result = ['-p', 'tcp', '-m', 'conntrack', '--ctorigdst', s['host'], '--ctorigdstport', str(s['port']),
                  '!', '-s', s['subnet']]
        if tagged:
            result += ['-m', 'comment', '--comment', self.tag]
        return result + ['-j', 'DROP']

    def nft_expr(self):
        s = self.spec
        subnet = ipaddress.ip_network(s['subnet'])
        def match(left, right, op='=='):
            return {'match': {'op': op, 'left': left, 'right': right}}
        return [match({'meta': {'key': 'l4proto'}}, 'tcp'),
                match({'ct': {'key': 'daddr', 'dir': 'original'}}, s['host']),
                match({'ct': {'key': 'proto-dst', 'dir': 'original'}}, s['port']),
                match({'payload': {'protocol': 'ip', 'field': 'saddr'}},
                      {'prefix': {'addr': str(subnet.network_address), 'len': subnet.prefixlen}}, '!='), {'drop': None}]

    def nft_rules(self):
        result = self.privileged(['nft', '-j', 'list', 'table', 'ip', self.table], check=False)
        if result.returncode:
            return None
        values = json.loads(result.stdout)['nftables']
        chain = next((x['chain'] for x in values if x.get('chain', {}).get('name') == 'forward'), None)
        if not chain or chain.get('hook') != 'forward' or chain.get('type') != 'filter' or chain.get('prio') != -1 or chain.get('policy') != 'accept':
            raise LanError('La tabla nftables de esta instalación tiene una estructura inesperada; revisión manual necesaria.')
        return [x['rule'] for x in values if 'rule' in x]

    def windows_names(self):
        return ['PixelChiss-' + self.identity + '-' + self.tag.rsplit(':', 1)[1] + '-' + kind
                for kind in ('Allow', 'Outside', 'Profiles')]

    def windows_query(self):
        names = ','.join(ps_quote(n) for n in self.windows_names())
        script = "$out=@(); foreach($n in @(" + names + ")) { "
        script += "$r=Get-NetFirewallRule -PolicyStore ActiveStore -Name $n -ErrorAction SilentlyContinue; if($r){"
        script += "$a=$r|Get-NetFirewallAddressFilter; $p=$r|Get-NetFirewallPortFilter; "
        script += "$out+=@{name=$r.Name;group=$r.Group;enabled=[string]$r.Enabled;action=[string]$r.Action;"
        script += "direction=[string]$r.Direction;profile=[string]$r.Profile;local=@($a.LocalAddress);remote=@($a.RemoteAddress);"
        script += "port=[string]$p.LocalPort;protocol=[string]$p.Protocol}}}; "
        script += "if(@(Get-NetFirewallProfile | Where-Object {-not $_.Enabled}).Count){throw 'Firewall profile disabled'};"
        script += 'ConvertTo-Json -InputObject @($out) -Depth 5 -Compress'
        return json.loads(powershell(script).stdout)

    def windows_expected(self):
        s = self.spec
        outside = sorted(str(n) for n in ipaddress.ip_network('0.0.0.0/0').address_exclude(ipaddress.ip_network(s['subnet'])))
        other = sorted({'Private', 'Public', 'Domain'} - {s['profile']})
        return [('Allow', [s['subnet']], [s['profile']]), ('Block', outside, ['Any']), ('Block', ['Any'], other)]

    def windows_row_valid(self, index, r):
        action, remote, profiles = self.windows_expected()[index]
        try:
            same_local = ipv4_ranges(r.get('local', [])) == ipv4_ranges([self.spec['host']])
            same_remote = ipv4_ranges(r.get('remote', [])) == ipv4_ranges(remote)
        except (ValueError, TypeError):
            return False
        return (r.get('name') == self.windows_names()[index] and r.get('group') == 'PixelChiss-' + self.identity and r.get('enabled') == 'True'
                    and r.get('direction') == 'Inbound' and r.get('action') == action
                    and same_local and r.get('port') == str(self.spec['port'])
                    and r.get('protocol') in ('TCP', '6') and same_remote
                and sorted(p.strip() for p in r.get('profile', '').split(',')) == sorted(profiles))

    def valid(self):
        if self.backend == 'iptables':
            if self.privileged(['iptables', '-C', 'DOCKER-USER'] + self.ipt_rule(True), check=False).returncode == 0:
                self.legacy = False
                return True
            self.legacy = self.privileged(['iptables', '-C', 'DOCKER-USER'] + self.ipt_rule(False), check=False).returncode == 0
            return self.legacy
        if self.backend == 'nftables':
            return any(r.get('comment') == self.tag and r.get('expr') == self.nft_expr() for r in self.nft_rules() or [])
        rows = self.windows_query()
        if len(rows) != 3:
            return False
        rows = {r['name']: r for r in rows}
        return all(self.windows_row_valid(i, rows.get(name, {})) for i, name in enumerate(self.windows_names()))

    def windows_elevated(self, script):
        self.consent()
        admin = powershell("if(([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)){exit 0}else{exit 3}", check=False)
        if not admin.returncode:
            powershell(script)
            return
        import base64
        # The elevated payload contains only validated addresses/rule names; no Python or secret files.
        encoded = base64.b64encode(("$ErrorActionPreference='Stop'; try {" + script + ";exit 0}catch{exit 1}").encode('utf-16le')).decode()
        powershell("$p=Start-Process powershell.exe -Verb RunAs -Wait -PassThru -ArgumentList "
                   + ps_quote('-NoProfile -NonInteractive -EncodedCommand ' + encoded) + '; exit $p.ExitCode')

    def ensure(self):
        self.detect(allow_prompt=True)
        if self.valid():
            print('Firewall: protección existente verificada, sin duplicar reglas.')
            return
        self.consent()
        if self.backend == 'iptables':
            self.privileged(['iptables', '-I', 'DOCKER-USER', '1'] + self.ipt_rule())
        elif self.backend == 'nftables':
            existing = self.nft_rules()
            commands = []
            if existing is None:
                commands += [{'add': {'table': {'family': 'ip', 'name': self.table}}},
                             {'add': {'chain': {'family': 'ip', 'table': self.table, 'name': 'forward',
                                               'type': 'filter', 'hook': 'forward', 'prio': -1, 'policy': 'accept'}}}]
            commands += [{'add': {'rule': {'family': 'ip', 'table': self.table, 'chain': 'forward',
                                           'expr': self.nft_expr(), 'comment': self.tag}}}]
            document = json.dumps({'nftables': commands})
            self.privileged(['nft', '-j', '-c', '-f', '-'], input=document)
            self.privileged(['nft', '-j', '-f', '-'], input=document)
        else:
            s = self.spec
            script = ''
            existing = {r['name']: r for r in self.windows_query()}
            # Block rules precede Allow, and old protection is kept until successful startup.
            order = [1, 2, 0]
            for i in order:
                name = self.windows_names()[i]
                action, remote, profiles = self.windows_expected()[i]
                if name in existing:
                    if not self.windows_row_valid(i, existing[name]):
                        raise LanError('Existe una regla inesperada con el nombre administrado; revisión manual necesaria.')
                    continue  # Resume a partial failure without duplicating valid rules.
                script += 'if(Get-NetFirewallRule -Name ' + ps_quote(name) + ' -ErrorAction SilentlyContinue){'
                script += 'throw "Existing unexpected rule: manual review required"};'
                script += 'New-NetFirewallRule -Name ' + ps_quote(name) + ' -DisplayName ' + ps_quote(name)
                script += ' -Group ' + ps_quote('PixelChiss-' + self.identity)
                script += ' -Direction Inbound -Enabled True -Protocol TCP -LocalAddress ' + s['host']
                script += ' -LocalPort ' + str(s['port']) + ' -RemoteAddress @(' + ','.join(ps_quote(x) for x in remote) + ')'
                script += ' -Profile ' + ','.join(profiles) + ' -Action ' + action + ' | Out-Null;'
            self.windows_elevated(script)
        if not self.valid():
            raise LanError('No se pudo verificar la nueva protección; el Gateway no se publicará.')
        print('Firewall: nueva protección instalada y verificada.')

    def retire_previous(self):
        """Called only after new protection and HTTPS health have been verified."""
        if not self.valid():
            raise LanError('Se perdió la protección; no se retirarán reglas anteriores.')
        if self.backend == 'iptables':
            rules = self.privileged(['iptables', '-S', 'DOCKER-USER']).stdout.splitlines()
            stale = []
            for line in rules:
                tokens = shlex.split(line)
                if '--comment' not in tokens:
                    continue
                comment = tokens[tokens.index('--comment') + 1]
                if comment.startswith(self.prefix) and comment != self.tag:
                    stale.append(tokens)
            if stale:
                self.consent()
                for tokens in stale:
                    self.privileged(['iptables', '-D'] + tokens[1:])
        elif self.backend == 'nftables':
            stale = [r for r in self.nft_rules() or [] if r.get('comment', '').startswith(self.prefix) and r.get('comment') != self.tag]
            if stale:
                self.consent()
                for rule in stale:
                    self.privileged(['nft', 'delete', 'rule', 'ip', self.table, 'forward', 'handle', str(rule['handle'])])
        else:
            keep = ','.join(ps_quote(n) for n in self.windows_names())
            script = 'Get-NetFirewallRule -Group ' + ps_quote('PixelChiss-' + self.identity)
            script += ' -ErrorAction SilentlyContinue | Where-Object {$_.Name -notin @(' + keep + ')}'
            script += ' | Remove-NetFirewallRule'
            # Query first to avoid elevation/confirmation on an unchanged run.
            query = '(@(Get-NetFirewallRule -Group ' + ps_quote('PixelChiss-' + self.identity)
            query += ' -ErrorAction SilentlyContinue | Where-Object {$_.Name -notin @(' + keep + ')})).Count'
            if int(powershell(query).stdout.strip() or '0'):
                self.windows_elevated(script)

    def undo_text(self):
        if self.backend == 'iptables':
            if self.legacy:
                return '# Se reutilizó una regla anterior sin etiqueta: no se creó ni se autoriza eliminar esa regla ajena.'
            return shlex.join(['sudo', 'iptables', '-D', 'DOCKER-USER'] + self.ipt_rule())
        if self.backend == 'nftables':
            return f'sudo nft -a list chain ip {self.table} forward  # retirar solo el handle con comment {self.tag}'
        return 'Remove-NetFirewallRule -Name ' + ','.join(self.windows_names())
