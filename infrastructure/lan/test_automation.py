"""No real firewall/Compose writes: mutations are replaced by mocks or temp files."""
import argparse
import base64
import contextlib
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import auto_docker as docker
import auto_firewall as firewall
import auto_network as network
import auto_start as start
import auto_support as support
import auto_tls as tls
import lan


def result(stdout='', code=0):
    return SimpleNamespace(stdout=stdout, stderr='', returncode=code)


def net(address='10.20.30.40', subnet='10.20.30.0/24', interface='adapter', profile='Private'):
    return network.Network(interface, address, subnet, '10.20.30.1', profile, True)


class NetworkTest(unittest.TestCase):
    def test_one_private_physical_interface(self):
        self.assertEqual('10.20.30.0/24', network.candidate('adapter', '10.20.30.40', 24).subnet)
        self.assertEqual(net(), network.choose([net()]))

    def test_no_eligible_interfaces(self):
        with self.assertRaises(support.LanError): network.choose([])

    def test_public_loopback_linklocal_ipv6_and_host_routes_rejected(self):
        for address, prefix in [('8.8.8.8',24), ('127.0.0.1',8), ('169.254.1.2',16), ('::1',128), ('10.1.2.3',32)]:
            self.assertIsNone(network.candidate('adapter', address, prefix))

    def test_linux_filters_docker_virtual_down_and_nonphysical(self):
        rows=[{'ifname':n,'flags':['UP'],'addr_info':[{'family':'inet','scope':'global','local':'10.20.30.40','prefixlen':24}]} for n in ['enp7s1','docker0','br-x','veth9','tun0','other']]
        rows.append({'ifname':'down','flags':[]})
        selected=network.linux_candidates(rows,[{'dst':'default','dev':'enp7s1','gateway':'10.20.30.1'}],lambda n:n=='enp7s1')
        self.assertEqual(1,len(selected)); self.assertEqual('enp7s1',selected[0].interface)

    def test_windows_physical_public_profile_retained(self):
        rows=[{'interface':'custom name','physical':True,'status':'Up','address':'10.20.30.40','prefix':24,'profile':'Public'}]
        self.assertEqual('Public',network.windows_candidates(rows)[0].profile)
        rows[0]['physical']=False; self.assertEqual([],network.windows_candidates(rows))

    def test_multiple_interfaces_never_default_arbitrarily(self):
        with self.assertRaises(support.LanError):network.choose([net(),net('10.8.0.2','10.8.0.0/24','second')],interactive=False)

    def test_manual_selection_and_bounds(self):
        second=net('10.8.0.2','10.8.0.0/24','second')
        self.assertEqual(second,network.choose([net(),second],input_fn=lambda _:'2',interactive=True))
        for value in ['0','3','x']:
            with self.assertRaises(support.LanError):network.choose([net(),second],input_fn=lambda _:value,interactive=True)

    def test_host_and_interface_must_be_actually_detected(self):
        self.assertEqual(net(),network.choose([net()],host='10.20.30.40',interface='adapter',subnet='10.20.30.0/24'))
        with self.assertRaises(support.LanError):network.choose([net()],host='10.20.30.41')

    def test_subnet_cannot_be_widened(self):
        with self.assertRaises(support.LanError):network.choose([net()],subnet='10.0.0.0/8')

    def test_ip_subnet_and_interface_changes_are_distinct(self):
        self.assertNotEqual(net().to_dict(),net('10.20.30.41').to_dict())
        self.assertNotEqual(net().to_dict(),net(subnet='10.20.0.0/16').to_dict())
        self.assertNotEqual(net().to_dict(),net(interface='another').to_dict())


class FilesTest(unittest.TestCase):
    def test_env_update_preserves_all_unrelated_secrets_and_comments(self):
        text="# comment\nLAN_BIND_ADDRESS=10.1.2.3\nLAN_TLS_PASSWORD='do not change'\nLAN_TLS_DIRECTORY='/private path'\nGOOGLE_DESKTOP_CLIENT_SECRET=secret\n"
        updated=support.update_env(text,{'LAN_BIND_ADDRESS':'10.20.30.40','LAN_HTTPS_PORT':8443})
        for line in text.splitlines()[2:]:self.assertIn(line,updated)
        self.assertIn('LAN_BIND_ADDRESS=10.20.30.40',updated)
        self.assertEqual(updated,support.update_env(updated,{'LAN_BIND_ADDRESS':'10.20.30.40','LAN_HTTPS_PORT':8443}))

    def test_env_parser_quotes_bom_comments(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'lan.env';p.write_text("\ufeffLAN_TLS_PASSWORD='abc'\nLAN_TLS_DIRECTORY='/a b'\n# ignore\nX=foo # comment\n")
            self.assertEqual({'LAN_TLS_PASSWORD':'abc','LAN_TLS_DIRECTORY':'/a b','X':'foo'},support.read_env(p))

    def test_atomic_write_private_mode(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'lan.env';support.atomic_write(p,'secret')
            self.assertEqual('secret',p.read_text())
            if os.name!='nt':self.assertEqual(0o600,stat.S_IMODE(p.stat().st_mode))

    def test_failed_replace_keeps_previous_and_removes_temporary(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'lan.env';p.write_text('previous')
            with patch('auto_support.os.replace',side_effect=OSError('fault')):
                with self.assertRaises(OSError):support.atomic_write(p,'next')
            self.assertEqual('previous',p.read_text());self.assertEqual(['lan.env'],os.listdir(d))

    def test_recover_interrupted_update_keeps_backups(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);support.atomic_write(root/'lan.env','old');support.atomic_write(root/'server.p12',b'key')
            tx=support.Transaction(root);tx.begin();support.atomic_write(root/'lan.env','new');support.atomic_write(root/'auto-state.json','{}')
            support.Transaction(root).restore()
            self.assertEqual('old',(root/'lan.env').read_text());self.assertFalse((root/'auto-state.json').exists())
            self.assertEqual(b'key',(tx.backup/'server.p12').read_bytes());self.assertFalse((root/'transaction.json').exists())

    def test_transaction_refuses_previous_journal(self):
        with tempfile.TemporaryDirectory() as d:
            tx=support.Transaction(Path(d));tx.begin()
            with self.assertRaises(support.LanError):support.Transaction(Path(d)).begin()

    def test_recovery_rejects_path_traversal(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);(root/'transaction.json').write_text(json.dumps({'backup':'../outside','present':[]}))
            with self.assertRaises(support.LanError):support.Transaction(root).restore()

    def test_concurrent_start_lock(self):
        with tempfile.TemporaryDirectory() as d:
            with support.operation_lock(Path(d)):
                with self.assertRaises(support.LanError):
                    with support.operation_lock(Path(d)):pass
            self.assertFalse((Path(d)/'auto.lock').exists())

    def test_no_noninteractive_approval(self):
        with patch('auto_support.sys.stdin.isatty',return_value=False):
            with self.assertRaises(support.LanError):support.confirm('security')

    def test_subprocess_failure_does_not_expose_arguments_or_output(self):
        with patch('auto_support.subprocess.run',return_value=result('SECRET',1)):
            with self.assertRaises(support.LanError) as error:support.run(['tool','PRIVATE_PASSWORD'])
        self.assertNotIn('SECRET',str(error.exception));self.assertNotIn('PRIVATE_PASSWORD',str(error.exception))

    def test_windows_powershell_uses_utf8_for_non_ascii_interface_names(self):
        import base64
        with patch('auto_support.run',return_value=result('\ufeffConexión')) as execute:
            self.assertEqual('Conexión',support.powershell('Get-NetAdapter').stdout)
        payload=base64.b64decode(execute.call_args.args[0][-1]).decode('utf-16le')
        self.assertIn('UTF8Encoding',payload);self.assertEqual('utf-8',execute.call_args.kwargs['encoding'])

    @unittest.skipIf(os.name=='nt','POSIX permission check')
    def test_world_readable_private_key_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'server.p12';p.write_bytes(b'key');p.chmod(0o644)
            with self.assertRaises(support.LanError):support.private_permissions(Path(d))

    @unittest.skipIf(os.name=='nt','symlink privileges differ on Windows')
    def test_atomic_write_rejects_symlinks(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);(root/'file').write_text('old');(root/'link').symlink_to(root/'file')
            with self.assertRaises(support.LanError):support.atomic_write(root/'link','new')
            self.assertEqual('old',(root/'file').read_text())

    @unittest.skipIf(os.name=='nt','symlink privileges differ on Windows')
    def test_private_backups_cannot_escape_through_directory_symlink(self):
        with tempfile.TemporaryDirectory() as d,tempfile.TemporaryDirectory() as outside:
            root=Path(d);support.atomic_write(root/'server.p12','private')
            (root/'.backups').symlink_to(outside,target_is_directory=True)
            with self.assertRaises(support.LanError):support.Transaction(root).begin()
            self.assertEqual([],os.listdir(outside))


class TlsTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
        for name,data in {'lan.env':'LAN_TLS_PASSWORD=private\n','server.p12':'server key','server.crt':'server public','ca.crt':'same CA'}.items():
            support.atomic_write(self.root/name,data)
        self.addCleanup(patch.stopall)
        patch('auto_tls.fingerprint',return_value='fixed-ca').start()
        patch('lan.keytool',side_effect=lambda *a,**k:(self.root/'.renewal'/'server.csr').write_text('CSR')).start()

    def install(self,args):
        p=Path(args.directory);support.atomic_write(p/'ca.crt',Path(args.ca_certificate).read_bytes());support.atomic_write(p/'server.crt','signed')

    def test_valid_certificate_reused_without_signing(self):
        with patch('auto_tls.verify',return_value='OK'),patch('lan.sign') as sign:
            self.assertIsNone(tls.prepare(self.root,'10.20.30.40','jdk'));sign.assert_not_called()
            self.assertFalse((self.root/'.renewal').exists())

    @unittest.skipIf(os.name=='nt','symlink privileges differ on Windows')
    def test_renewal_cannot_copy_private_key_through_directory_symlink(self):
        with tempfile.TemporaryDirectory() as outside:
            (self.root/'.renewal').symlink_to(outside,target_is_directory=True)
            with patch('auto_tls.verify',return_value='TLS_IP'):
                with self.assertRaises(support.LanError):tls.prepare(self.root,'10.20.30.40','jdk')
            self.assertEqual([],os.listdir(outside))

    def test_private_ca_renews_ip_preserving_ca_key_and_password(self):
        support.atomic_write(self.root/'ca.p12','CA PRIVATE')
        with patch('auto_tls.verify',side_effect=['TLS_IP','OK']),patch('lan.sign',side_effect=lambda a:Path(a.output).write_text('signed')) as sign,patch('lan.install',side_effect=self.install):
            pending=tls.prepare(self.root,'10.20.30.40','jdk')
        self.assertEqual(self.root/'.renewal',pending);self.assertFalse((pending/'ca.p12').exists())
        self.assertEqual('same CA',(self.root/'ca.crt').read_text());self.assertEqual('CA PRIVATE',(self.root/'ca.p12').read_text())
        self.assertEqual('LAN_TLS_PASSWORD=private\n',(pending/'lan.env').read_text());sign.assert_called_once()

    def test_expired_leaf_renewable_with_same_ca(self):
        support.atomic_write(self.root/'ca.p12','CA PRIVATE')
        with patch('auto_tls.verify',side_effect=['TLS_EXPIRED','OK']),patch('lan.sign',side_effect=lambda a:Path(a.output).write_text('signed')),patch('lan.install',side_effect=self.install):
            self.assertIsNotNone(tls.prepare(self.root,'10.20.30.40','jdk'))

    def test_common_ca_generates_one_resumable_csr_without_new_ca(self):
        with patch('auto_tls.verify',return_value='TLS_IP'),patch('lan.sign') as sign,patch('lan.init') as init:
            for _ in range(2):
                with self.assertRaises(support.LanError):tls.prepare(self.root,'10.20.30.40','jdk')
        self.assertTrue((self.root/'.renewal/server.csr').exists());sign.assert_not_called();init.assert_not_called()
        self.assertFalse((self.root/'.renewal/ca.p12').exists())

    def test_common_ca_signed_certificate_install_and_pin(self):
        approved=self.root/'approved.crt';approved.write_text('signed')
        with patch('auto_tls.verify',side_effect=['TLS_IP','OK']),patch('lan.install',side_effect=self.install),patch('lan.sign') as sign:
            self.assertIsNotNone(tls.prepare(self.root,'10.20.30.40','jdk',approved));sign.assert_not_called()

    def test_first_common_ca_can_resume_after_signature(self):
        (self.root/'server.crt').unlink();(self.root/'server.csr').write_text('first CSR')
        approved=self.root/'approved.crt';approved.write_text('signed')
        with patch('auto_tls.verify',return_value='OK'),patch('lan.install',side_effect=self.install):
            self.assertIsNotNone(tls.prepare(self.root,'10.20.30.40','jdk',approved))

    def test_invalid_chain_key_ca_expiry_never_silently_regenerate(self):
        for status in ['TLS_CHAIN','TLS_KEY','TLS_CA_EXPIRED','TLS_INVALID','TLS_NOT_YET_VALID']:
            with patch('auto_tls.verify',return_value=status),patch('lan.sign') as sign:
                with self.assertRaises(support.LanError):tls.prepare(self.root,'10.20.30.40','jdk')
                sign.assert_not_called()

    def test_invalid_signed_certificate_cannot_replace_production(self):
        approved=self.root/'approved.crt';approved.write_text('bad')
        with patch('auto_tls.verify',side_effect=['TLS_IP','TLS_CHAIN']),patch('lan.install',side_effect=self.install):
            with self.assertRaises(support.LanError):tls.prepare(self.root,'10.20.30.40','jdk',approved)
        self.assertEqual('server public',(self.root/'server.crt').read_text())

    def test_pending_csr_for_different_network_is_not_overwritten(self):
        p=self.root/'.renewal';p.mkdir(mode=0o700);(p/'pending.json').write_text(json.dumps({'host':'10.8.0.2','ca':'fixed-ca'}))
        with patch('auto_tls.verify',return_value='TLS_IP'):
            with self.assertRaises(support.LanError):tls.prepare(self.root,'10.20.30.40','jdk')
        archived=list((self.root/'.renewal-history').iterdir())
        self.assertEqual(1,len(archived));self.assertIn('10.8.0.2',(archived[0]/'pending.json').read_text())

    def test_ca_identity_change_is_rejected_after_staged_signature(self):
        approved=self.root/'approved.crt';approved.write_text('signed')
        with patch('auto_tls.verify',side_effect=['TLS_IP','OK']),patch('lan.install',side_effect=self.install),patch('auto_tls.fingerprint',side_effect=['old','new','old']):
            with self.assertRaises(support.LanError):tls.prepare(self.root,'10.20.30.40','jdk',approved)


class FirewallTest(unittest.TestCase):
    def setUp(self):
        self.fw=firewall.Firewall('0123456789abcdef',firewall.scope(net(),8443))

    def test_rule_scope_has_no_global_accept_or_flush(self):
        rule=self.fw.ipt_rule();self.assertIn('10.20.30.0/24',rule);self.assertEqual('DROP',rule[-1]);self.assertNotIn('ACCEPT',rule)
        self.assertIn('--ctorigdstport',rule);self.assertNotIn('-F',rule)

    def test_firewall_scope_rejects_wide_or_wrong_subnet(self):
        for n in [net(subnet='0.0.0.0/0'),net(subnet='10.1.0.0/16')]:
            with self.assertRaises(support.LanError):firewall.scope(n,8443)

    def test_same_network_stable_tag_new_network_new_tag(self):
        self.assertEqual(self.fw.tag,firewall.Firewall(self.fw.identity,firewall.scope(net(),8443)).tag)
        self.assertNotEqual(self.fw.tag,firewall.Firewall(self.fw.identity,firewall.scope(net(subnet='10.20.0.0/16'),8443)).tag)

    def test_existing_rule_does_not_require_duplicate_add(self):
        self.fw.backend='iptables'
        with patch.object(self.fw,'detect'),patch.object(self.fw,'valid',return_value=True),patch.object(self.fw,'privileged') as execute,patch.object(self.fw,'consent') as consent:
            self.fw.ensure();execute.assert_not_called();consent.assert_not_called()

    def test_legacy_untagged_rule_recognized_but_not_deleted(self):
        self.fw.backend='iptables'
        with patch.object(self.fw,'privileged',side_effect=[result('',1),result('',0)]):self.assertTrue(self.fw.valid())
        with patch.object(self.fw,'valid',return_value=True),patch.object(self.fw,'privileged',return_value=result('-A DOCKER-USER -j RETURN\n')) as execute:
            self.fw.retire_previous();self.assertEqual(1,execute.call_count)

    def test_authorization_precedes_mutation_and_verification(self):
        self.fw.backend='iptables';events=[]
        with patch.object(self.fw,'detect'),patch.object(self.fw,'valid',side_effect=[False,True]),patch.object(self.fw,'consent',side_effect=lambda:events.append('consent')),patch.object(self.fw,'privileged',side_effect=lambda *a,**k:events.append('write')):
            self.fw.ensure()
        self.assertEqual(['consent','write'],events)

    def test_failed_application_never_reports_protected(self):
        self.fw.backend='iptables'
        with patch.object(self.fw,'detect'),patch.object(self.fw,'valid',return_value=False),patch.object(self.fw,'consent'),patch.object(self.fw,'privileged',side_effect=support.LanError('failure')):
            with self.assertRaises(support.LanError):self.fw.ensure()

    def test_declined_firewall_authorization_never_writes(self):
        self.fw.backend='iptables'
        with patch.object(self.fw,'detect'),patch.object(self.fw,'valid',return_value=False),patch.object(self.fw,'consent',side_effect=support.LanError('cancel')),patch.object(self.fw,'privileged') as execute:
            with self.assertRaises(support.LanError):self.fw.ensure()
            execute.assert_not_called()

    def test_cleanup_only_previous_owned_comments(self):
        self.fw.backend='iptables'
        old='-A DOCKER-USER -m comment --comment '+self.fw.prefix+'old -j DROP'
        foreign='-A DOCKER-USER -m comment --comment another-application -j DROP'
        current='-A DOCKER-USER -m comment --comment '+self.fw.tag+' -j DROP'
        with patch.object(self.fw,'valid',return_value=True),patch.object(self.fw,'consent'),patch.object(self.fw,'privileged',return_value=result('\n'.join([old,foreign,current]))) as execute:
            self.fw.retire_previous()
        self.assertEqual(2,execute.call_count);self.assertIn(self.fw.prefix+'old',execute.call_args.args[0]);self.assertNotIn('another-application',execute.call_args.args[0])

    def test_cleanup_refuses_to_remove_old_protection_if_new_is_missing(self):
        with patch.object(self.fw,'valid',return_value=False),patch.object(self.fw,'privileged') as execute:
            with self.assertRaises(support.LanError):self.fw.retire_previous()
            execute.assert_not_called()

    def test_nft_batch_is_scoped_atomic_and_keeps_foreign_tables(self):
        self.fw.backend='nftables'
        with patch.object(self.fw,'detect'),patch.object(self.fw,'valid',side_effect=[False,True]),patch.object(self.fw,'consent'),patch.object(self.fw,'nft_rules',return_value=None),patch.object(self.fw,'privileged',return_value=result()) as execute:
            self.fw.ensure()
        self.assertEqual(2,execute.call_count)
        payload=execute.call_args.kwargs['input'];self.assertNotIn('flush',payload);self.assertNotIn('docker-bridges',payload)
        self.assertIn('10.20.30.40',payload);self.assertIn('proto-dst',payload)

    def test_windows_scope_blocks_complement_and_other_profiles(self):
        expected=self.fw.windows_expected()
        self.assertEqual(('Allow',['10.20.30.0/24'],['Private']),expected[0]);self.assertEqual(['Any'],expected[1][2])
        self.assertEqual(['Domain','Public'],expected[2][2])
        self.fw.spec['profile']='Public';self.assertEqual(['Domain','Private'],self.fw.windows_expected()[2][2])

    def test_windows_address_output_normalized_without_widening_access(self):
        expected=firewall.ipv4_ranges(['10.20.30.0/24'])
        self.assertEqual(expected,firewall.ipv4_ranges(['10.20.30.0/255.255.255.0']))
        self.assertEqual(expected,firewall.ipv4_ranges(['10.20.30.0-10.20.30.255']))
        self.assertNotEqual(expected,firewall.ipv4_ranges(['10.20.0.0/16']))

    def test_windows_validates_actual_rule_filters(self):
        self.fw.backend='windows';rows=[]
        for name,(action,remote,profiles) in zip(self.fw.windows_names(),self.fw.windows_expected()):
            rows.append({'name':name,'group':'PixelChiss-'+self.fw.identity,'enabled':'True','direction':'Inbound','action':action,
                         'local':['10.20.30.40'],'port':'8443','protocol':'TCP','remote':remote,'profile':', '.join(profiles)})
        with patch.object(self.fw,'windows_query',return_value=rows):
            self.assertTrue(self.fw.valid());rows[0]['remote']=['Any'];self.assertFalse(self.fw.valid())

    def test_windows_partial_failure_resumes_existing_valid_blocks(self):
        self.fw.backend='windows';rows=[]
        for index in [1,2]:
            action,remote,profiles=self.fw.windows_expected()[index]
            rows.append({'name':self.fw.windows_names()[index],'group':'PixelChiss-'+self.fw.identity,'enabled':'True','direction':'Inbound',
                         'action':action,'local':['10.20.30.40'],'port':'8443','protocol':'TCP','remote':remote,'profile':', '.join(profiles)})
        with patch.object(self.fw,'detect'),patch.object(self.fw,'valid',side_effect=[False,True]),patch.object(self.fw,'consent'),patch.object(self.fw,'windows_query',return_value=rows),patch.object(self.fw,'windows_elevated') as apply:
            self.fw.ensure()
        self.assertIn(self.fw.windows_names()[0],apply.call_args.args[0]);self.assertNotIn(self.fw.windows_names()[1],apply.call_args.args[0])

    def test_windows_conflicting_owned_name_does_not_overwrite_foreign_rule(self):
        self.fw.backend = 'windows'
        foreign = {'name': self.fw.windows_names()[1], 'group': 'Another-application'}
        with patch.object(self.fw, 'detect'), patch.object(self.fw, 'valid', return_value=False), \
                patch.object(self.fw, 'consent'), patch.object(self.fw, 'windows_query', return_value=[foreign]), \
                patch.object(self.fw, 'windows_elevated') as apply:
            with self.assertRaisesRegex(support.LanError, 'regla inesperada'):
                self.fw.ensure()
            apply.assert_not_called()

    def test_windows_cleanup_limits_removal_to_installation_and_keeps_current_names(self):
        self.fw.backend = 'windows'
        with patch.object(self.fw, 'valid', return_value=True), \
                patch('auto_firewall.powershell', return_value=result('1')) as query, \
                patch.object(self.fw, 'windows_elevated') as apply:
            self.fw.retire_previous()
        for script in (query.call_args.args[0], apply.call_args.args[0]):
            self.assertIn("Get-NetFirewallRule -Group 'PixelChiss-" + self.fw.identity + "'", script)
            self.assertIn('Where-Object {$_.Name -notin @(', script)
            for name in self.fw.windows_names():
                self.assertIn(support.ps_quote(name), script)
        self.assertNotIn('Remove-NetFirewallRule', query.call_args.args[0])
        self.assertIn(' | Remove-NetFirewallRule', apply.call_args.args[0])

    def test_native_nft_backend_detected_without_editing_docker_tables(self):
        with patch('auto_firewall.sys.platform','linux'),patch('auto_firewall.os.geteuid',return_value=0,create=True),patch.object(self.fw,'available',return_value=True),patch.object(self.fw,'privileged',return_value=result(json.dumps({'nftables':[{'table':{'name':'docker-bridges'}}]}))):
            self.fw.detect();self.assertEqual('nftables',self.fw.backend)

    def test_iptables_chain_without_early_forward_jump_is_not_trusted(self):
        with patch('auto_firewall.sys.platform','linux'),patch('auto_firewall.os.geteuid',return_value=0,create=True),patch.object(self.fw,'available',side_effect=lambda n:n=='iptables'),patch.object(self.fw,'privileged',return_value=result('-A FORWARD -j ACCEPT')):
            with self.assertRaises(support.LanError):self.fw.detect()

    def test_no_ownership_undo_for_legacy_manual_rule(self):
        self.fw.backend='iptables';self.fw.legacy=True
        self.assertNotIn('iptables -D',self.fw.undo_text())

    @patch('auto_firewall.sys.platform','linux')
    @patch('auto_firewall.os.geteuid',return_value=1000,create=True)
    def test_absence_of_privileges_never_silently_continues(self,_):
        with patch('auto_firewall.run',return_value=result('',1)):
            with self.assertRaises(support.LanError):self.fw.detect(allow_prompt=False)


class DockerPolicyTest(unittest.TestCase):
    def config(self):
        directory=Path('/private/lan')
        services={n:{} for n in docker.CORE}
        services['api-gateway']={'environment':{'SPRING_PROFILES_ACTIVE':'lan'},'ports':[{'host_ip':'10.20.30.40','target':8080,'published':'8443'},{'host_ip':'127.0.0.1','target':8082,'published':'8080'}],
            'volumes':[{'target':'/app/tls/server.p12','source':str(directory/'server.p12'),'read_only':True}]}
        services['auth-service']={'environment':{'GOOGLE_DESKTOP_CLIENT_ID':'fixture.apps.googleusercontent.com'}}
        return {'services':services,'networks':{'private':{'driver':'bridge'}}},directory

    def test_compose_validates_only_tls_is_external(self):
        config,path=self.config();docker.validate_config(config,path)
        config['services']['redis']={'ports':[{'host_ip':'0.0.0.0','target':6379,'published':'6379'}]}
        with self.assertRaises(support.LanError):docker.validate_config(config,path)

    def test_local_port_uses_resolved_compose_value(self):
        config,path=self.config();config['services']['api-gateway']['ports'][1]['published']='18080'
        self.assertEqual(18080,docker.local_gateway_port(config))

    def test_compose_never_mounts_ca_private_directory_into_gateway(self):
        config,path=self.config();config['services']['api-gateway']['volumes'][0]['target']='/app/tls'
        with self.assertRaises(support.LanError):docker.validate_config(config,path)

    def test_compose_rejects_direct_routing_network(self):
        config,path=self.config();config['networks']['private']['driver_opts']={'com.docker.network.bridge.gateway_mode_ipv4':'nat-unprotected'}
        with self.assertRaises(support.LanError):docker.validate_config(config,path)

    def test_custom_existing_project_is_preserved(self):
        base=Path('/checkout/infrastructure/docker/docker-compose.yml')
        self.assertEqual('teamhost',docker.select_project([{'Name':'teamhost','ConfigFiles':str(base)}],base))

    def test_explicit_project_cannot_duplicate_checkout(self):
        base=Path('/checkout/compose.yml')
        with self.assertRaises(support.LanError):docker.select_project([{'Name':'existing','ConfigFiles':str(base)}],base,'another')

    def test_ambiguous_project_requires_selection(self):
        base=Path('/checkout/compose.yml')
        with self.assertRaises(support.LanError):docker.select_project([{'Name':n,'ConfigFiles':str(base)} for n in ['one','two']],base)

    def test_fresh_project_has_existing_default_name(self):
        self.assertEqual('docker',docker.select_project([],Path('/checkout/compose.yml')))

    def test_active_game_reads_hash_and_never_modifies_state(self):
        obj=object.__new__(docker.Docker);obj.configuration={'services':{'game-service':{'environment':{'REDIS_URL':'redis://redis:6379/2'}}}}
        obj.call=Mock(return_value=result('[2,0]'))
        self.assertEqual(2,obj.active_games());args=obj.call.call_args.args[0]
        self.assertIn('2',args);self.assertIn('HGET',args[-2]);self.assertNotIn("redis.call('DEL'",args[-2])

    def test_unknown_or_unreachable_game_store_is_conservative(self):
        obj=object.__new__(docker.Docker);obj.configuration={'services':{'game-service':{'environment':{'REDIS_URL':'redis://redis:6379/0'}}}}
        for response in [result('[0,1]'),result('',1),result('invalid')]:
            obj.call=Mock(return_value=response);self.assertIsNone(obj.active_games())

    def test_game_in_progress_requires_maintenance_confirmation(self):
        obj=Mock();obj.active_games.return_value=1
        with patch('auto_start.confirm') as confirm:self.assertTrue(start.approve_disruption(obj,['api-gateway']));confirm.assert_called_once()

    def test_unknown_games_also_require_confirmation(self):
        obj=Mock();obj.active_games.return_value=None
        with patch('auto_start.confirm') as confirm:self.assertTrue(start.approve_disruption(obj,['api-gateway']));confirm.assert_called_once()

    def test_no_recreation_no_game_query_or_confirmation(self):
        obj=Mock()
        with patch('auto_start.confirm') as confirm:self.assertFalse(start.approve_disruption(obj,[]));confirm.assert_not_called();obj.active_games.assert_not_called()

    def test_images_rebuilt_only_when_missing_or_sources_change(self):
        obj=object.__new__(docker.Docker);obj.configuration={'services':{n:{'image':n} for n in docker.CORE}}
        obj.source_hashes=Mock(return_value={n:'same' for n in docker.CORE})
        with patch('auto_docker.run',return_value=result()):
            self.assertEqual([],obj.build_plan({})[0])
            self.assertEqual(['auth-service'],obj.build_plan({'auth-service':'old'})[0])
        with patch('auto_docker.run',return_value=result('',1)):self.assertEqual(list(docker.CORE),obj.build_plan({})[0])

    def test_unchanged_services_are_not_planned_for_recreation(self):
        obj=object.__new__(docker.Docker);obj.configuration={'services':{'api-gateway':{'image':'gateway'}}}
        obj.before={'api-gateway':{'State':{'Running':True},'Config':{'Labels':{'com.docker.compose.config-hash':'same'}},'Image':'id'}}
        obj.call=Mock(return_value=result('api-gateway same'))
        with patch('auto_docker.run',return_value=result('id\n')):self.assertEqual([],obj.changes([]))

    def test_runtime_internal_port_exposure_is_rejected(self):
        obj=object.__new__(docker.Docker);obj.configuration={'services':{'api-gateway':{'ports':[{'host_ip':'10.20.30.40','published':'8443'}]}}}
        with self.assertRaises(support.LanError):obj.validate_runtime({'redis':{'NetworkSettings':{'Ports':{'6379/tcp':[{'HostIp':'0.0.0.0','HostPort':'6379'}]}}}})

    def test_healthcheck_failure_stops_readiness(self):
        obj=object.__new__(docker.Docker);obj.configuration={'services':{'api-gateway':{}}}
        obj.containers=Mock(return_value={'api-gateway':{'State':{'Running':True,'Health':{'Status':'unhealthy'}}}})
        with self.assertRaises(support.LanError):obj.wait_health(0)

    def test_docs_only_change_does_not_change_image_source_hash(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);service=root/'auth-service';service.mkdir();(service/'src').mkdir();(service/'src/main.kt').write_text('source');(service/'README.md').write_text('old')
            obj=object.__new__(docker.Docker);obj.root=root
            with patch('auto_docker.run',return_value=result('auth-service/src/main.kt\0auth-service/README.md\0')):
                before=obj.source_hashes();(service/'README.md').write_text('new');self.assertEqual(before,obj.source_hashes())
                (service/'src/main.kt').write_text('changed');self.assertNotEqual(before,obj.source_hashes())


class StartWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
        self.directory=self.root/'infrastructure/docker/lan-secrets';self.directory.mkdir(parents=True,mode=0o700)
        support.atomic_write(self.directory.parent/'.env','GOOGLE_DESKTOP_CLIENT_ID=real.apps.googleusercontent.com\nUNCHANGED=private\n')
        support.atomic_write(self.directory/'lan.env',"LAN_BIND_ADDRESS=10.20.30.40\nLAN_HTTPS_PORT=8443\nLAN_TLS_PASSWORD=private\nLAN_TLS_DIRECTORY='/same/directory'\n")
        support.atomic_write(self.directory/'server.p12','server key');support.atomic_write(self.directory/'server.crt','public');support.atomic_write(self.directory/'ca.crt','public CA')
        self.args=argparse.Namespace(tls_directory=str(self.directory),host=None,interface=None,subnet=None,project=None,port=None,
                    ca_certificate=None,signed_certificate=None,image='jdk',rebuild=False,timeout=1)
        self.client=Mock();self.client.project='custom-project';self.client.before={'api-gateway':{'State':{'Running':True}}}
        self.client.config.side_effect=lambda:{'services':{'api-gateway':{'ports':[{'host_ip':support.read_env(self.directory/'lan.env')['LAN_BIND_ADDRESS'],'published':str(self.args.port or 8443)},{'host_ip':'127.0.0.1','target':8082,'published':'8080'}]}}}
        self.client.build_plan.return_value=([],{'auth-service':'hash'});self.client.changes.return_value=[];self.client.active_games.return_value=0
        self.fw=Mock();self.fw.backend='iptables';self.fw.spec=firewall.scope(net(),8443);self.fw.tag='tag';self.fw.undo_text.return_value='safe undo'
        self.addCleanup(patch.stopall)
        for target,value in [('lan.ROOT',self.root),('auto_start.network_for',Mock(return_value=net())),('auto_start.auto_docker.Docker',Mock(return_value=self.client)),
             ('auto_start.Firewall',Mock(return_value=self.fw)),('auto_start.auto_tls.prepare',Mock(return_value=None)),('auto_start.auto_tls.verify',Mock(return_value='OK')),
             ('auto_start.auto_docker.gateway_health',Mock()),('auto_start.fingerprint',Mock(return_value='same-ca')),('auto_start.run',Mock(return_value=result())),('auto_start.confirm',Mock())]:
            patch(target,value).start()

    def execute(self):
        with contextlib.redirect_stdout(io.StringIO()):start.start(self.args)

    def cli(self, command, *extra):
        output, errors = io.StringIO(), io.StringIO()
        argv = ['lan.py', command, '--tls-directory', str(self.directory), *extra]
        with patch('sys.argv', argv), contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            code = lan.main()
        return code, output.getvalue(), errors.getvalue()

    def windows_firewall(self, selected=None):
        obj = firewall.Firewall(start.identity(self.root, self.client.project), firewall.scope(selected or net(), 8443))
        rows = []
        for name, (action, remote, profiles) in zip(obj.windows_names(), obj.windows_expected()):
            rows.append({'name': name, 'group': 'PixelChiss-' + obj.identity, 'enabled': 'True',
                         'direction': 'Inbound', 'action': action, 'local': [obj.spec['host']],
                         'port': '8443', 'protocol': 'TCP', 'remote': remote, 'profile': ', '.join(profiles)})
        return obj, rows

    def private_snapshot(self):
        return {name: (self.directory / name).read_bytes() for name in
                ('lan.env', 'server.p12', 'server.crt', 'ca.crt', 'auto-state.json', 'firewall-undo.txt')
                if (self.directory / name).exists()}

    def assert_no_service_changes(self):
        self.client.build_images.assert_not_called()
        self.client.start.assert_not_called()
        self.client.wait_health.assert_not_called()
        self.client.wait_discovery.assert_not_called()

    def test_cli_windows_firewall_check_reports_states_without_writes(self):
        obj, rows = self.windows_firewall()
        broad = [dict(row) for row in rows]
        broad[0]['remote'] = ['Any']
        cases = [('valid', result(json.dumps(rows)), 0), ('absent', result('[]'), 1),
                 ('partial', result(json.dumps(rows[:2])), 1),
                 ('wrong-scope', result(json.dumps(broad)), 1),
                 ('invalid-json', result('invalid private JSON'), 1),
                 ('powershell-failure', result('private output', 1), 1),
                 ('missing-executable', FileNotFoundError('private path'), 1)]
        before = self.private_snapshot()
        for name, response, expected in cases:
            with self.subTest(state=name), patch('auto_start.Firewall', return_value=obj), \
                    patch('auto_firewall.sys.platform', 'win32'), \
                    patch('auto_support.subprocess.run', side_effect=[response]) as execute, \
                    patch('auto_firewall.confirm') as consent:
                code, output, errors = self.cli('firewall-check')
                self.assertEqual(expected, code)
                self.assertEqual(expected == 0, 'Firewall instalado y verificado' in output)
                if expected:
                    self.assertTrue(errors)
                self.assertNotIn('private', errors)
                consent.assert_not_called()
                script = base64.b64decode(execute.call_args.args[0][-1]).decode('utf-16le')
                self.assertNotRegex(script, r'New-NetFirewall|Remove-NetFirewall|Start-Process')
                self.assertEqual(before, self.private_snapshot())
                self.assertFalse((self.directory / 'transaction.json').exists())
                self.assertFalse((self.directory / 'auto.lock').exists())
                self.assert_no_service_changes()

    def test_cli_windows_start_reuses_verified_rules_and_state_without_elevation(self):
        obj, rows = self.windows_firewall()
        scripts = []

        def query_only(command, **kwargs):
            script = base64.b64decode(command[-1]).decode('utf-16le')
            scripts.append(script)
            if '$out=@(); foreach' in script:
                return result(json.dumps(rows))
            if 'Where-Object {$_.Name -notin @(' in script:
                return result('0')
            self.fail('Unexpected PowerShell execution: ' + script)

        with patch('auto_start.Firewall', return_value=obj), patch('auto_firewall.sys.platform', 'win32'), \
                patch('auto_support.subprocess.run', side_effect=query_only), patch('auto_firewall.confirm') as consent:
            for attempt in range(2):
                code, output, errors = self.cli('start', '--auto')
                self.assertEqual(0, code, errors)
                self.assertIn('Firewall: verificado', output)
                state = support.read_json(self.directory / 'auto-state.json')
                self.assertEqual({'backend': 'windows', 'scope': obj.spec, 'tag': obj.tag}, state['firewall'])
                if attempt == 0:
                    before = self.private_snapshot()
                    backups = list((self.directory / '.backups').iterdir())
                else:
                    self.assertEqual(before, self.private_snapshot())
                    self.assertEqual(backups, list((self.directory / '.backups').iterdir()))
            consent.assert_not_called()
        self.assertTrue(scripts)
        for script in scripts:
            self.assertNotRegex(script, r'New-NetFirewall|Remove-NetFirewall|Start-Process')
        self.client.start.assert_not_called()

    def test_cli_windows_query_errors_roll_back_before_build_or_publication(self):
        self.execute()  # Existing installation/state, all under this test's temporary directory.
        before = self.private_snapshot()
        env_before = (self.directory.parent / '.env').read_bytes()
        obj, _ = self.windows_firewall(net('10.20.30.41'))
        for response in (result('private parser output', 1), FileNotFoundError('private executable path')):
            with self.subTest(error=type(response).__name__), \
                    patch('auto_start.Firewall', return_value=obj), \
                    patch('auto_start.network_for', return_value=net('10.20.30.41')), \
                    patch('auto_firewall.sys.platform', 'win32'), \
                    patch('auto_support.subprocess.run', side_effect=[response]) as execute, \
                    patch('auto_firewall.confirm') as consent, \
                    patch.object(obj, 'retire_previous') as retire:
                self.client.reset_mock()
                code, output, errors = self.cli('start', '--auto')
                self.assertEqual(1, code)
                self.assertIn('powershell.exe', errors)
                self.assertNotIn('private', errors)
                self.assertNotIn('Firewall: verificado', output)
                self.assertEqual(before, self.private_snapshot())
                self.assertEqual(env_before, (self.directory.parent / '.env').read_bytes())
                self.assertFalse((self.directory / 'transaction.json').exists())
                self.assertFalse((self.directory / 'auto.lock').exists())
                self.assertTrue(list((self.directory / '.backups').iterdir()))
                execute.assert_called_once()
                consent.assert_not_called()
                retire.assert_not_called()
                self.assert_no_service_changes()

    def test_cli_windows_lost_protection_stops_before_service_publication(self):
        obj, rows = self.windows_firewall()
        before = self.private_snapshot()
        with patch('auto_start.Firewall', return_value=obj), patch('auto_firewall.sys.platform', 'win32'), \
                patch('auto_support.subprocess.run', side_effect=[result(json.dumps(rows)), result('[]')]), \
                patch('auto_firewall.confirm') as consent, patch.object(obj, 'retire_previous') as retire:
            code, output, errors = self.cli('start', '--auto')
            self.assertEqual(1, code)
            self.assertIn('dejó de estar verificada', errors)
            self.assertNotIn('Firewall: verificado', output)
            self.assertEqual(before, self.private_snapshot())
            self.assertFalse((self.directory / 'transaction.json').exists())
            self.assertFalse((self.directory / 'auto.lock').exists())
            consent.assert_not_called()
            retire.assert_not_called()
            self.client.start.assert_not_called()
            self.client.wait_health.assert_not_called()

    def test_cli_windows_missing_rules_require_authorization_before_changes(self):
        obj, _ = self.windows_firewall()
        before = self.private_snapshot()
        with patch('auto_start.Firewall', return_value=obj), patch('auto_firewall.sys.platform', 'win32'), \
                patch('auto_support.subprocess.run', return_value=result('[]')) as execute, \
                patch('auto_firewall.confirm', side_effect=support.LanError('Operación cancelada')) as consent, \
                patch.object(obj, 'retire_previous') as retire:
            code, output, errors = self.cli('start', '--auto')
            self.assertEqual(1, code)
            self.assertIn('Operación cancelada', errors)
            self.assertNotIn('Firewall: verificado', output)
            self.assertEqual(before, self.private_snapshot())
            execute.assert_called_once()
            consent.assert_called_once()
            retire.assert_not_called()
            self.assert_no_service_changes()

    def test_first_adoption_preserves_project_secrets_and_existing_services(self):
        old=(self.directory.parent/'.env').read_bytes();self.execute()
        self.assertEqual(old,(self.directory.parent/'.env').read_bytes());self.assertEqual('custom-project',support.read_json(self.directory/'auto-state.json')['project'])
        self.client.start.assert_not_called();self.fw.ensure.assert_called_once()

    def test_repeated_execution_does_not_duplicate_certificates_backups_or_containers(self):
        self.execute();backups=list((self.directory/'.backups').iterdir());key=(self.directory/'server.p12').stat().st_mtime_ns
        self.execute();self.assertEqual(backups,list((self.directory/'.backups').iterdir()));self.assertEqual(key,(self.directory/'server.p12').stat().st_mtime_ns)
        self.client.start.assert_not_called()

    def test_ip_change_updates_only_host_preserving_password_and_directory(self):
        self.client.changes.return_value=['api-gateway']
        with patch('auto_start.network_for',return_value=net('10.20.30.41')):self.execute()
        v=support.read_env(self.directory/'lan.env');self.assertEqual('10.20.30.41',v['LAN_BIND_ADDRESS']);self.assertEqual('private',v['LAN_TLS_PASSWORD']);self.assertEqual('/same/directory',v['LAN_TLS_DIRECTORY'])
        self.client.start.assert_called_once()

    def test_changed_port_is_applied_and_other_values_preserved(self):
        self.args.port=9443;self.client.changes.return_value=['api-gateway'];self.execute()
        self.assertEqual('9443',support.read_env(self.directory/'lan.env')['LAN_HTTPS_PORT'])

    def test_failed_start_rolls_back_configuration_without_deleting_data(self):
        before=(self.directory/'lan.env').read_bytes();self.client.changes.return_value=['api-gateway'];self.client.start.side_effect=support.LanError('startup failed')
        with patch('auto_start.network_for',return_value=net('10.20.30.41')):
            with self.assertRaises(support.LanError):self.execute()
        self.assertEqual(before,(self.directory/'lan.env').read_bytes());self.fw.retire_previous.assert_not_called()

    def test_firewall_failure_prevents_service_changes_and_restores_env(self):
        before=(self.directory/'lan.env').read_bytes();self.fw.ensure.side_effect=support.LanError('denied')
        with patch('auto_start.network_for',return_value=net('10.20.30.41')):
            with self.assertRaises(support.LanError):self.execute()
        self.assertEqual(before,(self.directory/'lan.env').read_bytes());self.client.start.assert_not_called()

    def test_missing_google_id_stops_before_docker_or_initialization(self):
        support.atomic_write(self.directory.parent/'.env','GOOGLE_DESKTOP_CLIENT_ID=\n')
        with self.assertRaises(support.LanError):self.execute()
        self.fw.ensure.assert_not_called();self.client.config.assert_not_called()

    def test_public_network_requires_explicit_confirmation(self):
        with patch('auto_start.network_for',return_value=net(profile='Public')),patch('auto_start.confirm',side_effect=support.LanError('declined')):
            with self.assertRaises(support.LanError):self.execute()
        self.fw.ensure.assert_not_called()

    def test_active_game_decline_does_not_recreate_or_change_configuration(self):
        self.client.active_games.return_value=1;self.client.changes.return_value=['api-gateway']
        with patch('auto_start.confirm',side_effect=[None,support.LanError('maintenance declined')]):
            with self.assertRaises(support.LanError):self.execute()
        self.client.start.assert_not_called();self.fw.ensure.assert_not_called()

    def test_old_rules_retired_only_after_healthy_https_and_transaction_commit(self):
        events=[]
        self.fw.ensure.side_effect=lambda:events.append('protect')
        self.fw.retire_previous.side_effect=lambda:events.append('retire')
        with patch('auto_start.auto_docker.gateway_health',side_effect=lambda *a:events.append('https')):self.execute()
        self.assertEqual(['protect','https','retire'],events);self.assertFalse((self.directory/'transaction.json').exists())

    def test_new_game_appearing_before_recreation_requires_confirmation(self):
        self.client.changes.return_value=['api-gateway'];self.client.active_games.side_effect=[0,1]
        with patch('auto_start.confirm') as confirm:self.execute()
        self.assertEqual(2,confirm.call_count)  # New network trust + game detected at the second check.

    def test_game_check_after_build_and_before_any_recreation(self):
        events=[];self.client.changes.return_value=['api-gateway']
        self.client.build_plan.return_value=(['api-gateway'],{'auth-service':'hash'})
        self.client.build_images.side_effect=lambda _:events.append('build')
        self.client.active_games.side_effect=lambda:events.append('games') or 0
        self.client.start.side_effect=lambda *args:events.append('start')
        self.execute()
        self.assertLess(events.index('build'),len(events)-1)
        self.assertEqual(['build','games','start'],events[-3:])

    def test_recover_files_does_not_require_running_docker(self):
        tx=support.Transaction(self.directory);tx.begin();support.atomic_write(self.directory/'lan.env','BROKEN=interrupted\n')
        args=argparse.Namespace(command='recover',tls_directory=str(self.directory))
        with contextlib.redirect_stdout(io.StringIO()):start.inspect_command(args)
        self.assertIn('LAN_TLS_PASSWORD',(self.directory/'lan.env').read_text());self.client.config.assert_not_called()

    def test_stop_requires_approval_and_never_removes_volumes(self):
        args=argparse.Namespace(command='stop',tls_directory=str(self.directory),host=None,port=None,project=None)
        with contextlib.redirect_stdout(io.StringIO()),patch('auto_start.confirm') as confirm:start.inspect_command(args)
        confirm.assert_called_once();self.client.call.assert_called_once_with(['stop']);self.client.config.assert_not_called()

    def test_cli_start_auto_dispatches_same_workflow(self):
        with patch('sys.argv',['lan.py','start','--auto','--tls-directory',str(self.directory)]),contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0,lan.main())
        self.client.start.assert_not_called();self.fw.ensure.assert_called_once()


if __name__=='__main__':unittest.main()
