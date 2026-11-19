import argparse
import contextlib
import importlib.util
import io
import ipaddress
from pathlib import Path
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('lan',Path(__file__).with_name('lan.py'))
lan=importlib.util.module_from_spec(spec)
spec.loader.exec_module(lan)

class LanPolicyTest(unittest.TestCase):
    def test_public_loopback_wildcard_and_invalid_addresses_cannot_be_advertised(self):
        for value in ('0.0.0.0','127.0.0.1','8.8.8.8','::1','not-a-host'):
            with self.assertRaises(ValueError):lan.private_host(value)
        self.assertEqual('10.1.2.3',lan.private_host('10.1.2.3'))

    def test_certificates_are_never_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            existing=Path(directory,'ca.p12');existing.write_bytes(b'existing-private-key')
            with self.assertRaises(ValueError):
                lan.init(argparse.Namespace(host='10.1.2.3',port='8443',output=directory))
            self.assertEqual(b'existing-private-key',existing.read_bytes())

    def test_firewall_rejects_internet_scope_or_wrong_subnet(self):
        for subnet in ('0.0.0.0/0','10.2.0.0/16','192.168.0.0/16'):
            with self.assertRaises(ValueError):
                lan.firewall(argparse.Namespace(host='10.1.2.3',port='8443',subnet=subnet,platform='linux'))

    def test_linux_rules_target_only_original_host_and_tls_port_and_include_undo(self):
        output=io.StringIO()
        with contextlib.redirect_stdout(output):
            lan.firewall(argparse.Namespace(host='10.1.2.3',port='8443',subnet='10.1.2.0/24',platform='linux'))
        rules=output.getvalue()
        self.assertIn('DOCKER-USER',rules);self.assertIn('--ctorigdst 10.1.2.3 --ctorigdstport 8443',rules)
        self.assertIn('! -s 10.1.2.0/24 -j DROP',rules);self.assertIn('iptables -D',rules)

    def test_windows_block_ranges_cover_every_ipv4_address_outside_allowed_subnet(self):
        subnet=ipaddress.ip_network('10.1.2.0/24')
        outside=list(ipaddress.ip_network('0.0.0.0/0').address_exclude(subnet))
        self.assertEqual(2**32-subnet.num_addresses,sum(n.num_addresses for n in outside))
        self.assertFalse(any(n.overlaps(subnet) for n in outside))
        output=io.StringIO()
        with contextlib.redirect_stdout(output):
            lan.firewall(argparse.Namespace(host='10.1.2.3',port='8443',subnet=str(subnet),platform='windows'))
        self.assertIn('-Profile Private -Action Allow',output.getvalue())
        self.assertIn('-Profile Any -Action Block',output.getvalue())
        self.assertIn('Remove-NetFirewallRule',output.getvalue())

if __name__=='__main__':unittest.main()
