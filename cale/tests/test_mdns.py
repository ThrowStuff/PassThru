# SPDX-License-Identifier: GPL-3.0-only
"""Discovery tests use fake modules exclusively: no sockets or multicast."""
import socket
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch
from cale.mdns import Advertisement


class AdvertisementTests(unittest.TestCase):
    def fake_modules(self, addresses):
        self.zc = Mock()
        self.factory = Mock(return_value=self.zc)
        self.info = Mock()
        return {
            'zeroconf': SimpleNamespace(Zeroconf=self.factory, ServiceInfo=self.info,
                                       IPVersion=SimpleNamespace(V4Only=4)),
            'ifaddr': SimpleNamespace(get_adapters=lambda: [SimpleNamespace(
                ips=[SimpleNamespace(ip=addr) for addr in addresses])]),
        }

    def test_all_ipv4_interfaces_and_lifecycle(self):
        for platform in ('win32', 'linux', 'darwin'):
            with self.subTest(platform=platform), patch.dict('sys.modules',
                    self.fake_modules(['127.0.0.1', '127.2.3.4', '0.0.0.0',
                                       ('::1', 0, 0), '10.1.2.4', '10.1.2.3', '10.1.2.3'])), \
                    patch('cale.mdns.sys.platform', platform), \
                    patch('cale.mdns.subprocess.Popen') as spawn:
                ad = Advertisement(48200, '0123456789abcdef9999', 'desktop')
                ad.start()
                ad.start()
                self.factory.assert_called_once_with(
                    interfaces=['10.1.2.3', '10.1.2.4'], ip_version=4)
                args, kwargs = self.info.call_args
                self.assertEqual(args, ('_passthru._tcp.local.', 'desktop._passthru._tcp.local.'))
                self.assertEqual(kwargs['addresses'], [socket.inet_aton(a) for a in
                                                      ('10.1.2.3', '10.1.2.4')])
                self.assertEqual(kwargs['properties'], {'fp': '0123456789abcdef', 'name': 'desktop'})
                self.assertEqual(kwargs['port'], 48200)
                self.zc.register_service.assert_called_once_with(self.info.return_value,
                                                                 allow_name_change=True)
                ad.stop()
                ad.stop()
                self.zc.unregister_service.assert_called_once_with(self.info.return_value)
                self.zc.close.assert_called_once()
                spawn.assert_not_called()

    def test_missing_import_fallback_only_linux(self):
        for platform in ('linux', 'win32', 'darwin'):
            with self.subTest(platform=platform), patch.dict('sys.modules', {'zeroconf': None}), \
                    patch('cale.mdns.sys.platform', platform), \
                    patch('cale.mdns.subprocess.Popen') as spawn, self.assertLogs(level='WARNING'):
                ad = Advertisement(48200, '0123456789abcdef9999', 'desktop')
                ad.start()
                if platform == 'linux':
                    self.assertEqual(spawn.call_args.args[0], ['avahi-publish-service',
                        'desktop', '_passthru._tcp', '48200', 'fp=0123456789abcdef', 'name=desktop'])
                    ad.stop()
                    spawn.return_value.terminate.assert_called_once()
                else:
                    spawn.assert_not_called()

    def test_registration_failure_closes_without_avahi(self):
        with patch.dict('sys.modules', self.fake_modules(['10.1.2.3'])), \
                patch('cale.mdns.subprocess.Popen') as spawn, self.assertLogs(level='ERROR'):
            self.zc.register_service.side_effect = OSError('network unavailable')
            ad = Advertisement(48200, '0123456789abcdef')
            ad.start()
            self.zc.close.assert_called_once()
            self.assertIsNone(ad.zeroconf)
            spawn.assert_not_called()

    def test_no_non_loopback_interfaces(self):
        with patch.dict('sys.modules', self.fake_modules(['127.0.0.1'])), \
                self.assertLogs(level='WARNING'):
            Advertisement(48200, '0123456789abcdef').start()
            self.factory.assert_not_called()

    def test_missing_avahi(self):
        with patch.dict('sys.modules', {'zeroconf': None}), \
                patch('cale.mdns.sys.platform', 'linux'), \
                patch('cale.mdns.subprocess.Popen', side_effect=FileNotFoundError), \
                self.assertLogs(level='WARNING') as logs:
            Advertisement(48200, '0123456789abcdef').start()
            self.assertIn('discovery disabled', logs.output[-1])
