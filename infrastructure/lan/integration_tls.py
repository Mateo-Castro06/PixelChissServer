"""Opt-in real JDK/keytool tests. Fresh private test CA only; no firewall or Compose writes."""
import argparse
import contextlib
import io
import os
from pathlib import Path
import tempfile
import unittest

import auto_tls
from auto_support import atomic_write, fingerprint, read_env
import lan


@unittest.skipUnless(os.getenv('PIXELCHISS_LAN_TLS_TEST') == '1', 'requires Docker JDK image; explicitly opt in')
class RealTlsLifecycle(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='pixelchiss-auto-tls-')
        cls.base = Path(cls.temp.name)
        cls.owner = cls.base / 'owner'
        cls.image = 'maven:3.9-eclipse-temurin-21'
        with contextlib.redirect_stdout(io.StringIO()):
            lan.init(argparse.Namespace(host='10.11.12.13', port='8443', output=str(cls.owner), image=cls.image))
        cls.ca_hash = fingerprint(cls.owner / 'ca.crt')

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_01_valid_wrong_ip_and_private_key_mismatch(self):
        self.assertEqual('OK', auto_tls.verify(self.owner, '10.11.12.13', self.image))
        self.assertEqual('TLS_IP', auto_tls.verify(self.owner, '10.21.22.23', self.image))
        original=(self.owner/'server.crt').read_bytes()
        try:
            atomic_write(self.owner/'server.crt',(self.owner/'ca.crt').read_bytes())
            self.assertEqual('TLS_KEY',auto_tls.verify(self.owner,'10.11.12.13',self.image))
        finally:atomic_write(self.owner/'server.crt',original)

    def test_02_real_renewal_reuses_ca_password_and_key(self):
        password=read_env(self.owner/'lan.env')['LAN_TLS_PASSWORD']
        pending=auto_tls.prepare(self.owner,'10.21.22.23',self.image)
        self.assertEqual('OK',auto_tls.verify(pending,'10.21.22.23',self.image))
        self.assertEqual(self.ca_hash,fingerprint(pending/'ca.crt'))
        self.assertEqual(password,read_env(pending/'lan.env')['LAN_TLS_PASSWORD'])
        self.assertFalse((pending/'ca.p12').exists())
        self.assertEqual('OK',auto_tls.verify(self.owner,'10.11.12.13',self.image))

    def test_03_common_ca_pending_sign_install_and_same_trust(self):
        guest=self.base/'guest'
        with contextlib.redirect_stdout(io.StringIO()):
            lan.init(argparse.Namespace(host='10.31.32.33',port='8443',output=str(guest),image=self.image),create_ca=False)
        atomic_write(guest/'ca.crt',(self.owner/'ca.crt').read_bytes())
        with self.assertRaises(ValueError):auto_tls.prepare(guest,'10.31.32.33',self.image)
        signed=self.base/'signed.crt'
        with contextlib.redirect_stdout(io.StringIO()):
            lan.sign(argparse.Namespace(host='10.31.32.33',csr=str(guest/'.renewal/server.csr'),ca_directory=str(self.owner),output=str(signed),image=self.image))
        pending=auto_tls.prepare(guest,'10.31.32.33',self.image,signed)
        self.assertEqual('OK',auto_tls.verify(pending,'10.31.32.33',self.image))
        self.assertEqual(self.ca_hash,fingerprint(pending/'ca.crt'))
        self.assertFalse((guest/'ca.p12').exists());self.assertFalse((pending/'ca.p12').exists())

    def test_04_wrong_ca_rejected_before_production_replacement(self):
        other=self.base/'other'
        with contextlib.redirect_stdout(io.StringIO()):
            lan.init(argparse.Namespace(host='10.41.42.43',port='8443',output=str(other),image=self.image))
        original=(self.owner/'ca.crt').read_bytes()
        try:
            atomic_write(self.owner/'ca.crt',(other/'ca.crt').read_bytes())
            self.assertEqual('TLS_CHAIN',auto_tls.verify(self.owner,'10.11.12.13',self.image))
        finally:atomic_write(self.owner/'ca.crt',original)

    def test_05_expired_leaf_detected_cryptographically(self):
        password=lan.password_file(self.owner)
        original_cert=(self.owner/'server.crt').read_bytes()
        original_store=(self.owner/'server.p12').read_bytes()
        try:
            args=argparse.Namespace(image=self.image)
            lan.keytool(args,[(self.owner,'/tls',False)],'/tls',password,[
                '-certreq','-alias','server','-file','expired.csr','-keystore','server.p12',
                '-storetype','PKCS12','-storepass:env','LAN_TLS_PASSWORD'])
            lan.keytool(args,[(self.owner,'/tls',False)],'/tls',password,[
                '-gencert','-alias','ca','-infile','expired.csr','-outfile','expired.crt','-rfc',
                '-startdate','-2d','-validity','1','-ext','SAN=ip:10.11.12.13','-ext','EKU=serverAuth',
                '-keystore','ca.p12','-storetype','PKCS12','-storepass:env','LAN_TLS_PASSWORD'])
            lan.keytool(args,[(self.owner,'/tls',False)],'/tls',password,[
                '-importcert','-alias','server','-file','expired.crt','-noprompt','-keystore','server.p12',
                '-storetype','PKCS12','-storepass:env','LAN_TLS_PASSWORD'])
            atomic_write(self.owner/'server.crt',(self.owner/'expired.crt').read_bytes())
            self.assertEqual('TLS_EXPIRED',auto_tls.verify(self.owner,'10.11.12.13',self.image))
        finally:
            atomic_write(self.owner/'server.crt',original_cert);atomic_write(self.owner/'server.p12',original_store)


if __name__ == '__main__':unittest.main(verbosity=2)
