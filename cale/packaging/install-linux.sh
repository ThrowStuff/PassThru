#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
# Usage: install-linux.sh [WHEELHOUSE]; absent WHEELHOUSE, pip uses PyPI.
[[ $# -le 1 ]] || { echo "Usage: $0 [WHEELHOUSE]" >&2; exit 2; }
source_dir=$(cd -- "$(dirname -- "$0")/.." && pwd)
install_dir="$HOME/.local/share/passthru-cale"
wheel_args=()
if [[ $# == 1 ]]; then
    wheelhouse=$(cd -- "$1" && pwd)
    wheel_args=(--no-index --find-links "$wheelhouse" --only-binary=:all:)
fi
mkdir -p "$install_dir" "$HOME/.local/bin" "$HOME/.local/share/applications"
python3 -m venv "$install_dir/venv"
"$install_dir/venv/bin/python" -m pip install "${wheel_args[@]}" -r "$source_dir/packaging/requirements-linux.txt"
rm -rf -- "$install_dir/cale"
cp -R -- "$source_dir/cale" "$install_dir/cale"
cp -- "$source_dir/../LICENSE" "$install_dir/LICENSE"
# Quote shell paths with bash's %q so spaces and metacharacters are safe.
{
    echo '#!/usr/bin/env bash'
    echo '# SPDX-License-Identifier: GPL-3.0-only'
    printf 'cd -- %q\n' "$install_dir"
    printf 'exec %q -m cale "$@"\n' "$install_dir/venv/bin/python"
} > "$HOME/.local/bin/passthru-cale"
chmod +x "$HOME/.local/bin/passthru-cale"
# Desktop Exec uses its own quoting rules, not shell quoting.
python3 - "$source_dir/packaging/passthru-cale.desktop" "$HOME/.local/bin/passthru-cale" "$HOME/.local/share/applications/passthru-cale.desktop" <<'PY'
import pathlib, sys
text = pathlib.Path(sys.argv[1]).read_text()
path = sys.argv[2]
for char in ('\\', '"', '`', '$'):
    path = path.replace(char, '\\' + char)
path = path.replace('%', '%%')
text = text.replace('Exec=python -m cale --gui', 'Exec="' + path + '" --gui')
pathlib.Path(sys.argv[3]).write_text(text)
PY
echo 'Installed. Run ~/.local/bin/passthru-cale --gui (add ~/.local/bin to PATH).'
