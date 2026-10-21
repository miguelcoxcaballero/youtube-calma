#!/usr/bin/env python3
"""Exercise production response filtering without credentials."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='calma-playback-tests-') as temp:
    classes = Path(temp) / 'classes'
    sources = [root / 'src/es/calma/youtube/nativeapp' / (name + '.java')
               for name in ['CalmaStreamResponse']]
    subprocess.run(['java', '-jar', str(root / 'tools/ecj.jar'), '-source', '1.8', '-target', '1.8',
                    '-proc:none', '-d', str(classes), *map(str, sources),
                    str(root / 'tests/PlaybackRegression.java')], check=True)
    subprocess.run(['java', '-cp', str(classes), 'es.calma.youtube.nativeapp.PlaybackRegression'], check=True)
