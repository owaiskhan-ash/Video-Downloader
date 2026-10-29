"""Allows `python -m engine ...`.

Without this file Python refuses to run the package directly:
    No module named engine.__main__; 'engine' is a package and cannot be
    directly executed
which is easy to miss because `python engine/main.py` *appears* to work while
silently breaking the relative imports.
"""

import sys

from .main import main

if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
