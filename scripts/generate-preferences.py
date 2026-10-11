#!/usr/bin/env python3
"""Encode the supplied preset in the base APK's existing preference format."""
import json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
values=json.loads((ROOT/'settings.json').read_text())
values.update(json.loads((ROOT/'navigation-settings.json').read_text()))
enums={'custom_branding_icon','spoof_video_streams_client_type','change_start_page'}
encoded={}
for key,value in values.items():
    stored=key if key.startswith('sb_') else 'revanced_'+key
    if key in enums:value=value.upper()
    # This APK uses boolean primitives; numbers, enums and text use strings.
    encoded[stored]=value if isinstance(value,bool) else str(value)
output=ROOT/'resources/assets/calma-preferences.json';output.parent.mkdir(parents=True,exist_ok=True)
output.write_text(json.dumps(encoded,indent=2,ensure_ascii=False)+'\n')
