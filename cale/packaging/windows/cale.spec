# SPDX-License-Identifier: GPL-3.0-only
from pathlib import Path
from PyInstaller.utils.hooks import collect_all

root = Path(SPECPATH).parents[1]
datas, binaries, hiddenimports = [], [], []
for package in ('pyaudiowpatch', 'cryptography', 'zeroconf', 'ifaddr'):
    d, b, h = collect_all(package)
    datas += d
    binaries += b
    hiddenimports += h
# PyInstaller's Qt hooks collect the Essentials modules and platform plugins.
hiddenimports += ['PySide6.QtCore', 'PySide6.QtGui', 'PySide6.QtWidgets']
datas += [(str(root.parent / 'LICENSE'), '.')]
a = Analysis([str(Path(SPECPATH) / 'launcher.py')], pathex=[str(root)],
             binaries=binaries, datas=datas, hiddenimports=hiddenimports)
pyz = PYZ(a.pure)
exe = EXE(pyz, a.scripts, [], exclude_binaries=True, name='PassThru Cale',
          console=False)
coll = COLLECT(exe, a.binaries, a.datas, name='PassThru Cale')
