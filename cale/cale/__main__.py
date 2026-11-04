# SPDX-License-Identifier: GPL-3.0-only
import argparse
import time
import threading
import sys
from .pairing import DeviceStore
from .mdns import Advertisement

def confirm(code, stdin=None, timeout=120):
    print("Pairing code:", code, "— codes match? [y/N]", flush=True)
    source = sys.stdin if stdin is None else stdin
    done, answer = threading.Event(), []
    def read():
        try:
            answer.append(source.readline().strip().lower() == 'y')
        finally:
            done.set()
    threading.Thread(target=read, daemon=True).start()
    return done.wait(timeout) and bool(answer and answer[0])

from .capture import ToneCapture, PipeWireCapture, WindowsLoopbackCapture
from .sender import Sender, fingerprint, generate_certificate


def main():
    parser = argparse.ArgumentParser(description='PassThru PCM TLS sender')
    source = parser.add_mutually_exclusive_group(required=False)
    source.add_argument('--tone', action='store_true')
    source.add_argument('--pipewire', action='store_true')
    source.add_argument('--system-audio', action='store_true',
                        help='capture the default system output')
    parser.add_argument('--target')
    parser.add_argument('--gui', action='store_true')
    parser.add_argument('--port', type=int, default=48200)
    parser.add_argument('--cert')
    parser.add_argument('--key')
    parser.add_argument('--host', default='0.0.0.0')
    parser.add_argument('--list-devices', action='store_true')
    parser.add_argument('--revoke')
    args = parser.parse_args()
    store = DeviceStore()
    if args.list_devices:
        for device in store.list():
            print(device['id'], device['name'], device['paired_at'])
        return
    if args.revoke:
        store.revoke(args.revoke)
        return
    if args.gui:
        from .gui import run_gui
        return run_gui(store, port=args.port, host=args.host, cert=args.cert, key=args.key)
    if not (args.tone or args.pipewire or args.system_audio):
        parser.error('choose --tone, --system-audio or --pipewire')
    args.cert, args.key = args.cert or store.cert, args.key or store.key
    capture = (ToneCapture() if args.tone else
               WindowsLoopbackCapture() if sys.platform == 'win32' else
               PipeWireCapture(args.target))
    sender = Sender(capture, args.cert, args.key, args.port, args.host, store=store, confirm=confirm)
    print('Certificate SHA-256:', fingerprint(args.cert), flush=True)
    print('Listening on port', sender.port, flush=True)
    advertisement = Advertisement(sender.port, fingerprint(args.cert))
    advertisement.start()
    sender.start()
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        pass
    finally:
        sender.stop()
        advertisement.stop()


if __name__ == '__main__':
    main()
