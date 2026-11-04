# SPDX-License-Identifier: GPL-3.0-only
"""Frozen GUI entry point; absolute import preserves package-relative imports."""
from cale.__main__ import main
import sys

if __name__ == '__main__':
    sys.argv.insert(1, '--gui')
    main()
