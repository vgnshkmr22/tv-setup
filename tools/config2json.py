#!/usr/bin/env python3
"""Write the launcher's config.xml as a config.json theme overlay, optionally with an existing overlay on top.

Used when a TV moves to the official launcher (build-and-install.sh MIGRATE=1): the official APK carries
the default config.xml, so the settings from yours have to travel as an overlay (themes/README.md).

Usage: config2json.py <config.xml> <out config.json> [overlay config.json whose keys win]
"""
import json
import sys
import xml.etree.ElementTree as ET


def convert(path):
    out = {}
    for el in ET.parse(path).getroot():
        name = el.get("name")
        if not name:
            continue
        text = (el.text or "").strip()
        if el.tag == "string-array":
            out[name] = [(item.text or "").strip() for item in el.findall("item")]
        elif el.tag == "string":
            out[name] = text
        elif el.tag == "bool":
            out[name] = text == "true"
        elif el.tag == "integer":
            out[name] = int(text)
    return out


def main():
    if len(sys.argv) not in (3, 4):
        sys.exit(__doc__)
    config = convert(sys.argv[1])
    if len(sys.argv) == 4:
        try:
            with open(sys.argv[3], encoding="utf-8") as f:
                overlay = json.load(f)
        except ValueError:
            overlay = None   # the launcher ignores a file that doesn't parse, so do the same
        if isinstance(overlay, dict):
            config.update(overlay)
    with open(sys.argv[2], "w", encoding="utf-8") as f:
        json.dump(config, f, indent=2)
        f.write("\n")
    print(f"wrote {sys.argv[2]} ({len(config)} keys)")


if __name__ == "__main__":
    main()
