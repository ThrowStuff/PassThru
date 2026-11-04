#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
rm -f -- "$HOME/.local/bin/passthru-cale" "$HOME/.local/share/applications/passthru-cale.desktop"
rm -rf -- "$HOME/.local/share/passthru-cale"
echo 'Uninstalled. Pairing keys and settings in ~/.config/passthru were retained.'
