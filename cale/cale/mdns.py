# SPDX-License-Identifier: GPL-3.0-only
"""Optional cross-platform IPv4 mDNS advertisement (imports stay lazy)."""
import ipaddress
import logging
import socket
import subprocess
import sys


class Advertisement:
    def __init__(self, port, fingerprint, name=None):
        self.port, self.fp = port, fingerprint
        self.name = name or socket.gethostname()
        self.process = None
        self.zeroconf = None
        self.info = None

    def start(self):
        if self.process is not None or self.zeroconf is not None:
            return
        try:
            from zeroconf import Zeroconf, ServiceInfo, IPVersion
            import ifaddr  # zeroconf's cross-platform interface enumeration dependency
        except ImportError:
            logging.warning('python-zeroconf unavailable; install zeroconf for mDNS discovery')
            if sys.platform.startswith('linux'):
                self._start_avahi()
            else:
                logging.warning('mDNS discovery disabled; use manual host entry')
            return
        try:
            addresses = sorted({ip.ip for adapter in ifaddr.get_adapters()
                                for ip in adapter.ips
                                if isinstance(ip.ip, str)
                                and ipaddress.ip_address(ip.ip).version == 4
                                and not ipaddress.ip_address(ip.ip).is_loopback
                                and not ipaddress.ip_address(ip.ip).is_unspecified})
            if not addresses:
                logging.warning('mDNS discovery disabled: no non-loopback IPv4 interfaces')
                return
            self.zeroconf = Zeroconf(interfaces=addresses, ip_version=IPVersion.V4Only)
            service_type = '_passthru._tcp.local.'
            self.info = ServiceInfo(service_type, self.name + '.' + service_type,
                addresses=[socket.inet_aton(addr) for addr in addresses],
                port=self.port, properties={'fp': self.fp[:16], 'name': self.name},
                server=socket.gethostname().rstrip('.') + '.local.')
            self.zeroconf.register_service(self.info, allow_name_change=True)
        except Exception:
            logging.exception('mDNS discovery disabled: zeroconf advertisement failed')
            self.stop()

    def _start_avahi(self):
        try:
            self.process = subprocess.Popen(['avahi-publish-service', self.name,
                '_passthru._tcp', str(self.port), 'fp=' + self.fp[:16], 'name=' + self.name],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except OSError:
            logging.warning('avahi-publish-service unavailable; discovery disabled')

    def stop(self):
        if self.zeroconf is not None:
            zc, info = self.zeroconf, self.info
            self.zeroconf, self.info = None, None
            try:
                if info is not None:
                    zc.unregister_service(info)
            except Exception:
                logging.exception('Could not unregister mDNS service')
            finally:
                try:
                    zc.close()
                except Exception:
                    logging.exception('Could not close mDNS sockets')
        if self.process is not None:
            self.process.terminate()
            try:
                self.process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()
            self.process = None
