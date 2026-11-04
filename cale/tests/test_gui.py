# SPDX-License-Identifier: GPL-3.0-only
import os
os.environ['QT_QPA_PLATFORM'] = 'offscreen'
import tempfile
import threading
import time
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

try:
    from PySide6.QtCore import QThread
    from PySide6.QtGui import QCloseEvent
    from PySide6.QtWidgets import QApplication
    from cale.gui import CaleWindow, PairRequest, PairingDialog, PairingBridge, parse_sinks, list_sources, status_text
except ImportError:
    QApplication = None

from cale.pairing import DeviceStore


@unittest.skipIf(QApplication is None, 'PySide6 unavailable')
class GuiTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.store = DeviceStore(self.temp.name)
        with patch('cale.gui.list_sources', return_value=[('sink.one', 'Speakers')]), \
             patch('cale.gui.QSystemTrayIcon.isSystemTrayAvailable', return_value=False):
            self.window = CaleWindow(self.store)
        self.addCleanup(self.window.deleteLater)
        self.addCleanup(self.window.stop)

    def pump_until(self, condition):
        deadline = time.monotonic() + 2
        while not condition() and time.monotonic() < deadline:
            self.app.processEvents()
            threading.Event().wait(.001)
        self.assertTrue(condition())

    def test_windows_system_audio(self):
        with patch('cale.gui.sys.platform', 'win32'), \
                patch('cale.gui.subprocess.run') as run:
            self.assertEqual(list_sources(), [])
            run.assert_not_called()
        with patch('cale.gui.sys.platform', 'win32'), \
                patch('cale.gui.WindowsLoopbackCapture') as capture, \
                patch('cale.gui.PipeWireCapture') as pipewire, \
                patch('cale.gui.Sender') as sender, \
                patch('cale.gui.Advertisement'), \
                patch('cale.gui.fingerprint', return_value='abc'):
            self.window.toggle_sender()
            capture.assert_called_once_with()
            pipewire.assert_not_called()
            self.assertIs(sender.call_args.args[0], capture.return_value)
            self.window.stop()

    def test_pairing_bridge(self):
        for answer in (True, False, None):
            with self.subTest(answer=answer):
                self.window.sender = Mock(connected_phone=None, capture_error=None)
                self.window.bridge.timeout = .05 if answer is None else 1
                result = []
                worker = threading.Thread(target=lambda: result.append(
                    self.window.bridge.confirm('012345')))
                worker.start()
                self.pump_until(lambda: self.window.dialog is not None)
                dialog = self.window.dialog
                self.assertEqual(dialog.thread(), QThread.currentThread())
                self.assertEqual(dialog.request.code, '012345')
                self.assertTrue(worker.is_alive())
                if answer is not None:
                    (dialog.match if answer else dialog.mismatch).click()
                self.pump_until(lambda: not worker.is_alive())
                worker.join()
                self.app.processEvents()
                self.assertEqual(result, [answer is True])
                self.assertFalse(dialog.isVisible())
                self.window.dialog = None
                self.window.sender = None

    def test_status_and_timer(self):
        self.assertEqual(self.window.timer.interval(), 1000)
        self.assertEqual(status_text(None), 'Stopped')
        fake = SimpleNamespace(connected_phone=None, last_stats=None, capture_error=None)
        self.assertEqual(status_text(fake), 'Listening')
        fake.connected_phone = 'Pixel'
        fake.last_stats = {'latency_ms': 57.5, 'occupancy_ms': 30}
        self.window.sender = fake
        self.window.update_status()
        self.assertEqual(self.window.status.text(),
                         'Streaming to Pixel · latency 57.5 ms · buffer 30 ms')
        self.window.sender = None

    def test_pairing_from_tray(self):
        self.window.sender = Mock(connected_phone=None, capture_error=None)
        self.window.tray = Mock()
        self.window.hide()
        with patch.object(self.window, 'show_window', wraps=self.window.show_window) as show:
            def create_dialog(*args):
                self.assertTrue(show.called)
                return PairingDialog(*args)
            with patch('cale.gui.PairingDialog', side_effect=create_dialog):
                self.window.show_pairing(PairRequest('012345'))
        self.assertTrue(self.window.isVisible())
        self.window.tray.showMessage.assert_called_once_with(
            'Pair phone', 'Confirm code 012345 in Cale')

    def test_capture_failure_status(self):
        fake = SimpleNamespace(connected_phone=None, last_stats=None,
                               capture_error='capture iterator ended')
        self.assertEqual(status_text(fake),
                         'Audio capture stopped — capture iterator ended · Listening')
        fake.connected_phone = 'Pixel'
        self.assertEqual(status_text(fake),
                         'Audio capture stopped — capture iterator ended · Streaming to Pixel')

    @patch('cale.gui.sys.platform', 'linux')
    def test_sources(self):
        sample = '[{"name":"sink.one","description":"Speakers"},{"name":"sink.two"}]'
        self.assertEqual(parse_sinks(sample), [('sink.one', 'Speakers'), ('sink.two', 'sink.two')])
        short = '1\tsink.one\tPipeWire\ts16le 2ch 48000Hz\tRUNNING\n'
        self.assertEqual(parse_sinks(short, True), [('sink.one', 'sink.one')])
        with patch('cale.gui.subprocess.run', side_effect=[
                SimpleNamespace(stdout='invalid'), SimpleNamespace(stdout=short)]) as run:
            self.assertEqual(list_sources(), [('sink.one', 'sink.one')])
            self.assertEqual(run.call_args.args[0], ['pactl', 'list', 'short', 'sinks'])
        self.assertEqual(self.window.sources.itemText(0), 'System audio')
        self.assertIsNone(self.window.sources.itemData(0))
        self.assertEqual(self.window.sources.itemData(1), 'sink.one')

    def test_revoke(self):
        device = self.store.add(b'k' * 32, 'Pixel')
        self.window.refresh_phones()
        self.assertEqual(self.window.phones.count(), 1)
        self.window.phones.setCurrentRow(0)
        self.window.sender = Mock(connected_phone=None, capture_error=None)
        self.window.sender.revoke_device.side_effect = self.store.revoke
        self.window.revoke.click()
        self.window.sender.revoke_device.assert_called_once_with(device['id'])
        self.assertEqual(self.window.phones.count(), 0)
        self.assertEqual(self.store.list(), [])
        self.assertFalse(self.window.revoke.isEnabled())

    @patch('cale.gui.sys.platform', 'linux')
    def test_start_stop_and_close(self):
        self.window.sources.setCurrentIndex(1)
        with patch('cale.gui.Sender') as sender, patch('cale.gui.PipeWireCapture') as capture, \
             patch('cale.gui.Advertisement') as ad:
            sender.return_value.connected_phone = None
            sender.return_value.capture_error = None
            self.window.toggle.click()
            capture.assert_called_once_with(target='sink.one')
            sender.return_value.start.assert_called_once()
            self.assertFalse(self.window.sources.isEnabled())
            self.window.close()
            sender.return_value.stop.assert_called_once()
            ad.return_value.stop.assert_called_once()
            self.assertEqual(self.window.status.text(), 'Stopped')

    def test_close_with_tray_keeps_sender(self):
        sender = Mock(connected_phone=None, capture_error=None)
        self.window.sender = sender
        self.window.tray = Mock()
        event = QCloseEvent()
        self.window.closeEvent(event)
        self.assertFalse(event.isAccepted())
        sender.stop.assert_not_called()
        self.window.stop()
        sender.stop.assert_called_once()


if __name__ == '__main__':
    unittest.main()
