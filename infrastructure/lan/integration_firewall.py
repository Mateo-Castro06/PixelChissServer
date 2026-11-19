"""Real Linux firewall adapters, ONLY in a disposable container with --network none.

Never run with host networking. Guard requires .dockerenv and exactly loopback.
"""
import json
import os
from pathlib import Path
import socket
import unittest
from unittest.mock import Mock

from auto_firewall import Firewall
from auto_support import run


@unittest.skipUnless(os.getenv('PIXELCHISS_FIREWALL_NAMESPACE_TEST') == '1', 'requires explicitly isolated network namespace')
class IsolatedFirewall(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not Path('/.dockerenv').exists() or {n for _, n in socket.if_nameindex()} != {'lo'}:
            raise RuntimeError('ABORTADO: este test solo funciona en Docker --network none; nunca en el firewall del anfitrión.')

    def adapter(self, identity, subnet='10.11.12.0/24'):
        obj=Firewall(identity,{'host':'10.11.12.13','subnet':subnet,'port':8443,'profile':'Private'})
        obj.consent=Mock()  # Authorization only for this disposable namespace.
        return obj

    def test_01_iptables_real_idempotency_network_change_and_foreign_rule(self):
        run(['iptables','-N','DOCKER-USER'])
        run(['iptables','-I','FORWARD','1','-j','DOCKER-USER'])
        run(['iptables','-A','DOCKER-USER','-m','comment','--comment','foreign-test-rule','-j','RETURN'])
        first=self.adapter('0123456789abcdef');first.ensure();first.ensure()
        self.assertEqual('iptables',first.backend)
        rules=run(['iptables','-S','DOCKER-USER']).stdout
        self.assertEqual(1,rules.count(first.tag))
        second=self.adapter(first.identity,'10.11.0.0/16');second.ensure();second.retire_previous()
        rules=run(['iptables','-S','DOCKER-USER']).stdout
        self.assertNotIn(first.tag,rules);self.assertEqual(1,rules.count(second.tag));self.assertIn('foreign-test-rule',rules)

    def test_02_nftables_real_json_batch_and_cleanup(self):
        # Only a fixture in this container, to simulate Docker's native backend marker.
        run(['nft','add','table','ip','docker-bridges'])
        first=self.adapter('fedcba9876543210')
        try:
            first.ensure();first.ensure()
        except ValueError:
            print('Reglas de la red de pruebas:',json.dumps(first.nft_rules()))
            print('Expresión esperada:',json.dumps(first.nft_expr()))
            raise
        self.assertEqual('nftables',first.backend)
        self.assertEqual(1,len(first.nft_rules()))
        second=self.adapter(first.identity,'10.11.0.0/16');second.ensure();second.retire_previous()
        self.assertEqual([second.tag],[r['comment'] for r in second.nft_rules()])
        self.assertIn('docker-bridges',run(['nft','list','tables']).stdout)


if __name__=='__main__':unittest.main(verbosity=2)
