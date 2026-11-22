"""Inspect generated PowerShell without executing any host firewall commands.

Delimiter checks always run. If PowerShell is installed, its real parser checks
every payload too; query execution uses local fixture functions, never NetSecurity.
"""
import base64
import contextlib
import io
import json
import re
import shutil
import subprocess
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from auto_firewall import Firewall
import auto_support


POWERSHELL = next((path for name in ('powershell.exe', 'pwsh', 'powershell')
                   if (path := shutil.which(name))), None)


def result(stdout='', code=0):
    return SimpleNamespace(stdout=stdout, stderr='', returncode=code)


def assert_delimiters(test, script):
    """Guard the delimiters/quotes in our generated subset, not a full PS parser."""
    stack = []
    quote = None
    index = 0
    pairs = {'}': '{', ')': '(', ']': '['}
    while index < len(script):
        char = script[index]
        if char == '`' and quote != "'":
            index += 2
            continue
        if quote:
            if char == quote:
                if index + 1 < len(script) and script[index + 1] == quote:
                    index += 2
                    continue
                quote = None
        elif char in "'\"":
            quote = char
        elif char in '{([':
            stack.append(char)
        elif char in pairs:
            test.assertTrue(stack, f'Unexpected {char} at {index}')
            test.assertEqual(pairs[char], stack.pop(), f'Mismatched {char} at {index}')
        index += 1
    test.assertIsNone(quote, 'Unclosed PowerShell string')
    test.assertEqual([], stack, 'Unclosed PowerShell delimiters')


class GeneratedPowerShellTest(unittest.TestCase):
    def setUp(self):
        self.fw = Firewall('0123456789abcdef', {
            'host': '10.20.30.40', 'subnet': '10.20.30.0/24',
            'port': 8443, 'profile': 'Private',
        })
        self.fw.backend = 'windows'

    def query_script(self):
        with patch('auto_firewall.powershell', return_value=result('[]')) as execute:
            self.assertEqual([], self.fw.windows_query())
        return execute.call_args.args[0]

    def generated_scripts(self):
        """Capture the actual generators, including both elevation paths."""
        scripts = {'query': self.query_script()}
        for profile in ('Private', 'Public', 'Domain'):
            self.fw.spec['profile'] = profile
            with patch.object(self.fw, 'detect'), \
                    patch.object(self.fw, 'valid', side_effect=[False, True]), \
                    patch.object(self.fw, 'windows_query', return_value=[]), \
                    patch.object(self.fw, 'consent'), \
                    patch('auto_firewall.powershell', return_value=result()) as execute, \
                    contextlib.redirect_stdout(io.StringIO()):
                self.fw.ensure()
            scripts['admin-' + profile], scripts['install-' + profile] = [
                call.args[0] for call in execute.call_args_list]

        install = scripts['install-Private']
        with patch.object(self.fw, 'consent'), \
                patch('auto_firewall.powershell', side_effect=[result(code=3), result()]) as execute:
            self.fw.windows_elevated(install)
        launcher = execute.call_args.args[0]
        scripts['elevation-launcher'] = launcher
        encoded = re.search(r'-EncodedCommand ([A-Za-z0-9+/=]+)', launcher)[1]
        scripts['elevated-payload'] = base64.b64decode(encoded).decode('utf-16le')

        with patch.object(self.fw, 'valid', return_value=True), \
                patch.object(self.fw, 'consent'), \
                patch('auto_firewall.powershell', side_effect=[result('1'), result(), result()]) as execute:
            self.fw.retire_previous()
        scripts['stale-query'], scripts['cleanup-admin'], scripts['cleanup'] = [
            call.args[0] for call in execute.call_args_list]
        scripts['undo'] = self.fw.undo_text()

        # Check the UTF-16LE transport and shared PowerShell prefix as sent to the process.
        for name, script in list(scripts.items()):
            with patch('auto_support.run', return_value=result()) as execute:
                auto_support.powershell(script)
            command = execute.call_args.args[0]
            self.assertEqual('powershell.exe', command[0])
            scripts['encoded-' + name] = base64.b64decode(command[-1]).decode('utf-16le')
            self.assertTrue(scripts['encoded-' + name].endswith(script))
        return scripts

    def test_all_generated_scripts_have_balanced_delimiters_and_quotes(self):
        for name, script in self.generated_scripts().items():
            with self.subTest(script=name):
                assert_delimiters(self, script)

    def test_query_closes_foreach_before_profile_check_and_serialization(self):
        script = self.query_script()
        assert_delimiters(self, script.split('if(@(Get-NetFirewallProfile', 1)[0])
        assert_delimiters(self, script.split('ConvertTo-Json', 1)[0])
        self.assertEqual(1, script.count('ConvertTo-Json'))
        self.assertIn('-PolicyStore ActiveStore', script)
        self.assertNotRegex(script, r'New-NetFirewall|Remove-NetFirewall|Start-Process')

    def test_guard_rejects_original_missing_foreach_brace(self):
        script = self.query_script()
        broken = script.replace('protocol=[string]$p.Protocol}}};',
                                'protocol=[string]$p.Protocol}};', 1)
        self.assertNotEqual(script, broken)
        with self.assertRaises(AssertionError):
            assert_delimiters(self, broken)

    def run_powershell(self, script, data):
        encoded = base64.b64encode(script.encode('utf-16le')).decode('ascii')
        return subprocess.run([POWERSHELL, '-NoProfile', '-NonInteractive', '-EncodedCommand', encoded],
                              input=json.dumps(data), text=True, encoding='utf-8',
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)

    @unittest.skipUnless(POWERSHELL, 'PowerShell not installed: real parser unavailable')
    def test_real_powershell_parser_accepts_all_payloads_and_rejects_original_bug(self):
        cases = [{'name': name, 'script': script, 'invalid': False}
                 for name, script in self.generated_scripts().items()]
        cases.append({'name': 'original-bug', 'invalid': True,
                      'script': self.query_script().replace('protocol=[string]$p.Protocol}}};',
                                                            'protocol=[string]$p.Protocol}};', 1)})
        parser = """
$ErrorActionPreference='Stop'
$cases = [Console]::In.ReadToEnd() | ConvertFrom-Json
foreach($case in $cases) {
    $tokens=$null; $errors=$null
    $null=[System.Management.Automation.Language.Parser]::ParseInput(
        $case.script, [ref]$tokens, [ref]$errors)
    if (($errors.Count -gt 0) -ne $case.invalid) {
        throw ($case.name + ': ' + ($errors | Out-String))
    }
}
"""
        completed = self.run_powershell(parser, cases)
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    @unittest.skipUnless(POWERSHELL, 'PowerShell not installed: fixture execution unavailable')
    def test_real_query_execution_with_fixture_cmdlets_serializes_array_and_checks_profiles(self):
        # These functions shadow cmdlets. No NetSecurity module or host state is used.
        fixtures = """
$ErrorActionPreference='Stop'
$fixture = [Console]::In.ReadToEnd() | ConvertFrom-Json
function Get-NetFirewallRule {
    [CmdletBinding()] param($Name, $PolicyStore)
    $fixture.rows | Where-Object { $_.name -eq $Name }
}
function Get-NetFirewallAddressFilter {
    param([Parameter(ValueFromPipeline=$true)]$Rule)
    process { [pscustomobject]@{LocalAddress=$Rule.local; RemoteAddress=$Rule.remote} }
}
function Get-NetFirewallPortFilter {
    param([Parameter(ValueFromPipeline=$true)]$Rule)
    process { [pscustomobject]@{LocalPort=$Rule.port; Protocol=$Rule.protocol} }
}
function Get-NetFirewallProfile { [pscustomobject]@{Enabled=$fixture.enabled} }
"""
        rows = []
        for name, (action, remote, profiles) in zip(self.fw.windows_names(), self.fw.windows_expected()):
            rows.append({'name': name, 'group': 'PixelChiss-' + self.fw.identity,
                         'enabled': 'True', 'action': action, 'direction': 'Inbound',
                         'profile': ', '.join(profiles), 'local': [self.fw.spec['host']],
                         'remote': remote, 'port': '8443', 'protocol': 'TCP'})
        for count in (0, 1, 3):
            with self.subTest(rules=count):
                completed = self.run_powershell(fixtures + self.query_script(),
                                                {'rows': rows[:count], 'enabled': True})
                self.assertEqual(0, completed.returncode, completed.stderr)
                self.assertEqual(rows[:count], json.loads(completed.stdout.lstrip('\ufeff')))
        completed = self.run_powershell(fixtures + self.query_script(), {'rows': rows, 'enabled': False})
        self.assertNotEqual(0, completed.returncode)
        self.assertIn('Firewall profile disabled', completed.stderr)


if __name__ == '__main__':
    unittest.main()
