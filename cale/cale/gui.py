# SPDX-License-Identifier: GPL-3.0-only
"""Small Qt desktop frontend. Audio and TLS remain owned by Sender."""
import json
import subprocess
import sys
import threading
from dataclasses import dataclass, field

from PySide6.QtCore import QObject, Qt, QTimer, Signal, Slot
from PySide6.QtGui import QIcon
from PySide6.QtWidgets import (QApplication, QComboBox, QDialog, QHBoxLayout,
    QLabel, QListWidget, QListWidgetItem, QMenu, QPushButton, QStyle,
    QSystemTrayIcon, QVBoxLayout, QWidget)

from .capture import PipeWireCapture, WindowsLoopbackCapture
from .mdns import Advertisement
from .pairing import DeviceStore
from .sender import Sender, fingerprint


def parse_sinks(output, short=False):
    if short:
        return [(parts[1], parts[1]) for line in output.splitlines()
                if len(parts := line.split()) >= 2]
    sinks = json.loads(output)
    if not isinstance(sinks, list):
        raise ValueError('expected sink list')
    return [(s['name'], s.get('description') or s['name']) for s in sinks
            if isinstance(s, dict) and isinstance(s.get('name'), str)]


def list_sources():
    if sys.platform == 'win32':
        return []
    for command, short in [(['pactl', '-f', 'json', 'list', 'sinks'], False),
                           (['pactl', 'list', 'short', 'sinks'], True)]:
        try:
            output = subprocess.run(command, capture_output=True, text=True,
                                    check=True, timeout=2).stdout
            return parse_sinks(output, short)
        except (OSError, subprocess.SubprocessError, ValueError, KeyError, TypeError):
            continue
    return []


@dataclass
class PairRequest:
    code: str
    done: threading.Event = field(default_factory=threading.Event)
    accepted: bool = False


class PairingBridge(QObject):
    requested = Signal(object)
    expired = Signal(object)

    def __init__(self, parent=None, timeout=120):
        super().__init__(parent)
        self.timeout = timeout

    def confirm(self, code):
        request = PairRequest(code)
        self.requested.emit(request)
        if not request.done.wait(self.timeout):
            request.done.set()
            self.expired.emit(request)
            return False
        return request.accepted


class PairingDialog(QDialog):
    def __init__(self, request, parent=None):
        super().__init__(parent)
        self.request = request
        self.setWindowTitle('Pair phone')
        layout = QVBoxLayout(self)
        layout.addWidget(QLabel('Check that this code matches the phone.'))
        code = QLabel(request.code)
        code.setAlignment(Qt.AlignmentFlag.AlignCenter)
        code.setStyleSheet('font-size: 36px; font-weight: bold; padding: 20px')
        layout.addWidget(code)
        buttons = QHBoxLayout()
        self.match = QPushButton('Match')
        self.mismatch = QPushButton("Doesn't match")
        buttons.addWidget(self.mismatch)
        buttons.addWidget(self.match)
        layout.addLayout(buttons)
        self.match.clicked.connect(self.accept)
        self.mismatch.clicked.connect(self.reject)
        self.timer = QTimer(self)
        self.timer.setSingleShot(True)
        self.timer.timeout.connect(self.reject)
        self.timer.start(120000)

    def done(self, result):
        if not self.request.done.is_set():
            self.request.accepted = result == QDialog.DialogCode.Accepted
            self.request.done.set()
        self.timer.stop()
        super().done(result)


def status_text(sender):
    if sender is None:
        return 'Stopped'
    phone = sender.connected_phone
    prefix = (f'Audio capture stopped — {sender.capture_error} · '
              if sender.capture_error else '')
    if phone is None:
        return prefix + 'Listening'
    stats = sender.last_stats
    text = prefix + f'Streaming to {phone}'
    if isinstance(stats, dict):
        latency = stats.get('latency_ms')
        buffer = stats.get('occupancy_ms')
        text += f" · latency {latency if latency is not None else '—'} ms · buffer {buffer if buffer is not None else '—'} ms"
    return text


class CaleWindow(QWidget):
    def __init__(self, store, *, port=48200, host='0.0.0.0', cert=None, key=None):
        super().__init__()
        self.store, self.port, self.host = store, port, host
        self.cert, self.key = cert or store.cert, key or store.key
        self.sender = self.advertisement = self.dialog = None
        self.bridge = PairingBridge(self)
        self.bridge.requested.connect(self.show_pairing, Qt.ConnectionType.QueuedConnection)
        self.bridge.expired.connect(self.expire_pairing, Qt.ConnectionType.QueuedConnection)
        self.setWindowTitle('Cale · PassThru')
        self.resize(530, 360)
        layout = QVBoxLayout(self)
        title = QLabel('Cale  ·  Desktop companion')
        title.setStyleSheet('font-size: 22px; font-weight: bold')
        layout.addWidget(title)
        self.status = QLabel('Stopped')
        self.status.setWordWrap(True)
        layout.addWidget(self.status)
        layout.addWidget(QLabel('Audio source'))
        self.sources = QComboBox()
        self.sources.addItem('System audio', None)
        for name, description in list_sources():
            self.sources.addItem(description, name)
        layout.addWidget(self.sources)
        self.toggle = QPushButton('Start')
        self.toggle.setObjectName('primary')
        self.toggle.clicked.connect(self.toggle_sender)
        layout.addWidget(self.toggle)
        layout.addWidget(QLabel('Paired phones'))
        self.phones = QListWidget()
        layout.addWidget(self.phones)
        self.revoke = QPushButton('Revoke')
        self.revoke.clicked.connect(self.revoke_phone)
        self.phones.itemSelectionChanged.connect(
            lambda: self.revoke.setEnabled(self.phones.currentItem() is not None))
        layout.addWidget(self.revoke)
        self.refresh_phones()
        self.tray = None
        if QSystemTrayIcon.isSystemTrayAvailable():
            icon = QIcon.fromTheme('audio-headphones', self.style().standardIcon(
                QStyle.StandardPixmap.SP_MediaVolume))
            self.tray = QSystemTrayIcon(icon, self)
            menu = QMenu(self)
            menu.addAction('Show Cale', self.show_window)
            menu.addAction('Quit', self.quit)
            self.tray.setContextMenu(menu)
            self.tray.activated.connect(lambda reason: self.show_window()
                if reason == QSystemTrayIcon.ActivationReason.Trigger else None)
            self.tray.show()
        self.timer = QTimer(self)
        self.timer.timeout.connect(self.update_status)
        self.timer.start(1000)
        hints = QApplication.styleHints()
        hints.colorSchemeChanged.connect(self.apply_theme)
        self.apply_theme()

    def apply_theme(self, *_):
        dark = QApplication.styleHints().colorScheme() == Qt.ColorScheme.Dark
        bg, panel, border, fg = ('#121214', '#2A2622', '#62574D', '#F6E9D6') if dark else (
            '#FFFFFF', '#F6F7F9', '#D9DDE3', '#1A1A1A')
        self.setStyleSheet(f'''
            QWidget {{ background: {bg}; color: {fg}; font-size: 14px; }}
            QPushButton, QComboBox, QListWidget {{ background: {panel};
                border: 1px solid {border}; border-radius: 6px; padding: 8px; }}
            QPushButton#primary {{ background: #E07A2F; color: #121214; font-weight: bold; }}
            QPushButton:disabled {{ color: #888888; }}
            QListWidget::item:selected {{ background: #E07A2F; color: #121214; }}
        ''')

    @Slot(object)
    def show_pairing(self, request):
        if request.done.is_set() or self.sender is None:
            request.done.set()
            return
        if self.dialog is not None:
            self.dialog.reject()
        self.show_window()
        if self.tray is not None:
            self.tray.showMessage('Pair phone', f'Confirm code {request.code} in Cale')
        self.dialog = PairingDialog(request, self)
        self.dialog.open()

    @Slot(object)
    def expire_pairing(self, request):
        if self.dialog is not None and self.dialog.request is request:
            self.dialog.reject()

    def refresh_phones(self):
        devices = self.store.list()
        snapshot = [(d['id'], d['name']) for d in devices]
        if snapshot == getattr(self, '_phones_snapshot', None):
            return
        self._phones_snapshot = snapshot
        self.phones.clear()
        for identity, name in snapshot:
            item = QListWidgetItem(f'{name} · {identity}')
            item.setData(Qt.ItemDataRole.UserRole, identity)
            self.phones.addItem(item)
        self.revoke.setEnabled(False)

    def revoke_phone(self):
        item = self.phones.currentItem()
        if item is not None:
            device_id = item.data(Qt.ItemDataRole.UserRole)
            if self.sender is not None:
                self.sender.revoke_device(device_id)
            else:
                self.store.revoke(device_id)
            self.refresh_phones()

    def update_status(self):
        self.status.setText(status_text(self.sender))
        self.refresh_phones()

    def toggle_sender(self):
        if self.sender is not None:
            self.stop()
            return
        try:
            capture = (WindowsLoopbackCapture() if sys.platform == 'win32' else
                       PipeWireCapture(target=self.sources.currentData()))
            self.sender = Sender(capture,
                self.cert, self.key, self.port, self.host, store=self.store,
                confirm=self.bridge.confirm)
            self.advertisement = Advertisement(self.sender.port, fingerprint(self.cert))
            self.advertisement.start()
            self.sender.start()
        except Exception as error:
            self.stop()
            self.status.setText(f'Could not start: {error}')
            return
        self.sources.setEnabled(False)
        self.toggle.setText('Stop')
        self.update_status()

    def stop(self):
        if self.dialog is not None:
            self.dialog.reject()
        if self.sender is not None:
            self.sender.stop()
            self.sender = None
        if self.advertisement is not None:
            self.advertisement.stop()
            self.advertisement = None
        self.sources.setEnabled(True)
        self.toggle.setText('Start')
        self.update_status()

    def show_window(self):
        self.showNormal()
        self.raise_()
        self.activateWindow()

    def quit(self):
        self.stop()
        QApplication.instance().quit()

    def closeEvent(self, event):
        if self.tray is not None:
            self.hide()
            event.ignore()
        else:
            self.stop()
            event.accept()


def run_gui(store=None, **options):
    app = QApplication(sys.argv)
    window = CaleWindow(store or DeviceStore(), **options)
    app.aboutToQuit.connect(window.stop)
    window.show()
    return app.exec()
