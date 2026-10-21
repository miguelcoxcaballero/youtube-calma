#!/usr/bin/env python3
"""Canonical entry point: retain embedded microG in all current builds."""
from pathlib import Path
import runpy

if __name__ == '__main__':
    runpy.run_path(str(Path(__file__).resolve().parents[1] /
                       'experiments/embedded-gms/build-youtube.py'), run_name='__main__')
