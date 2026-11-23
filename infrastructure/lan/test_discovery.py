"""Readiness checks compare downloaded data to the independently trusted local CA."""
import contextlib
import hashlib
import io
import json
from pathlib import Path
import ssl
import tempfile
import unittest
from unittest.mock import Mock, patch

import auto_docker
from auto_support import LanError


class DiscoveryReadinessTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.ca = Path(self.temp.name) / 'ca.crt'
        # Mock network/TLS only. Real X.509/PKIX and TLS are tested by Gateway/JDK.
        self.pem = '-----BEGIN CERTIFICATE-----\nY2FuZGlkYXRl\n-----END CERTIFICATE-----\n'
        self.ca.write_text(self.pem)
        self.hash = hashlib.sha256(ssl.PEM_cert_to_DER_cert(self.pem)).hexdigest()
        self.document = {'protocolVersion': 1, 'caId': 'sha256:' + self.hash,
                         'fingerprintSha256': self.hash, 'caPem': self.pem}

    def check(self, document):
        calls = []
        context = Mock()

        def get(url, **kwargs):
            calls.append((url, kwargs))
            body = json.dumps(document if url.endswith('/ca') else {'status': 'UP'}).encode()
            return contextlib.closing(io.BytesIO(body))

        with patch('auto_docker.ssl.create_default_context', return_value=context) as create, \
                patch('auto_docker.urllib.request.urlopen', side_effect=get):
            auto_docker.gateway_health('10.20.30.40', 8443, self.ca, timeout=0)
        create.assert_called_once_with(cafile=str(self.ca))
        self.assertTrue(all(options['context'] is context for url, options in calls if url.startswith('https:')))
        self.assertEqual(['https://10.20.30.40:8443/actuator/health',
                          'http://127.0.0.1:8080/actuator/health',
                          'https://10.20.30.40:8443/.well-known/pixelchiss/ca'], [url for url, _ in calls])

    def test_verified_discovery_matches_local_ca(self):
        self.check(self.document)

    def test_manipulated_ca_and_self_reported_fingerprint_cannot_replace_local_trust(self):
        for change in ({'caPem': self.pem.replace('Y2FuZGlkYXRl', 'YXR0YWNrZXI=')},
                       {'fingerprintSha256': '0' * 64}, {'caId': 'sha256:' + '0' * 64},
                       {'protocolVersion': 2}, {'caPem': 'not a certificate'}):
            with self.subTest(change=change), self.assertRaises(LanError):
                self.check(dict(self.document, **change))

    def test_invalid_or_oversized_documents_fail_readiness(self):
        for invalid in (None, [], {}, {'caPem': 10, 'protocolVersion': 1},
                        dict(self.document, protocolVersion=True), {'caPem': 'x' * 32769}):
            with self.subTest(type=type(invalid)), self.assertRaises(LanError):
                self.check(invalid)

    def test_network_or_strict_tls_failure_never_reports_ready(self):
        for error in (OSError('unavailable'), ssl.SSLCertVerificationError('untrusted/expired/SAN')):
            with self.subTest(error=type(error)), patch('auto_docker.ssl.create_default_context'), \
                    patch('auto_docker.urllib.request.urlopen', side_effect=error), self.assertRaises(LanError):
                auto_docker.gateway_health('10.20.30.40', 8443, self.ca, timeout=0)


if __name__ == '__main__':
    unittest.main()
