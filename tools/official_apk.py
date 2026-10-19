#!/usr/bin/env python3
"""Download the latest official launcher APK and check it against its update.json (docs/specs/self-update.md).

Usage: official_apk.py <out.apk>
       UPDATE_FEED=<url> official_apk.py <out.apk>     another feed, e.g. a fork's

Prints the version on success; exits non-zero, leaving no file behind, when anything doesn't check out.
"""
import hashlib
import json
import os
import re
import sys
import urllib.parse
import urllib.request

FEED = os.environ.get("UPDATE_FEED") or \
    "https://github.com/Gathin23/cleanyourtv/releases/latest/download/update.json"
PACKAGE = "com.gathin.tvhome"
MAX_FEED = 4 * 1024
MAX_APK = 20 * 1024 * 1024
UA = {"User-Agent": "CleanYourTV-installer"}


def fail(msg):
    sys.exit(f"official_apk: {msg}")


def fetch(url, limit):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=30) as r:
        data = r.read(limit + 1)
    if len(data) > limit:
        fail(f"{url} is larger than {limit} bytes")
    return data


def check_feed(feed):
    def need(cond, what):
        if not cond:
            fail(f"update.json: {what}")
    need(isinstance(feed, dict), "not an object")
    need(feed.get("schema") == 1, "schema is not 1")
    need(feed.get("package") == PACKAGE, f"package is not {PACKAGE}")
    for key in ("version_code", "min_sdk", "apk_size"):
        need(type(feed.get(key)) is int, f"{key} is not an integer")
    need(isinstance(feed.get("version_name"), str)
         and re.fullmatch(r"[0-9A-Za-z.-]{1,16}", feed["version_name"]), "bad version_name")
    url = feed.get("apk_url")
    need(isinstance(url, str) and urllib.parse.urlsplit(url).scheme == "https", "apk_url is not https")
    need(1 <= feed["apk_size"] <= MAX_APK, "apk_size out of range")
    need(isinstance(feed.get("apk_sha256"), str)
         and re.fullmatch(r"[0-9a-f]{64}", feed["apk_sha256"]), "bad apk_sha256")
    notes = feed.get("notes", "")
    need(isinstance(notes, str) and len(notes) <= 200, "bad notes")


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    out = sys.argv[1]
    try:
        feed = json.loads(fetch(FEED, MAX_FEED).decode("utf-8"))
    except (OSError, ValueError) as e:
        fail(f"could not read {FEED}: {e}")
    check_feed(feed)
    try:
        apk = fetch(feed["apk_url"], feed["apk_size"])
    except OSError as e:
        fail(f"could not download {feed['apk_url']}: {e}")
    if len(apk) != feed["apk_size"]:
        fail(f"APK is {len(apk)} bytes, update.json says {feed['apk_size']}")
    digest = hashlib.sha256(apk).hexdigest()
    if digest != feed["apk_sha256"]:
        fail(f"APK SHA-256 {digest} does not match update.json {feed['apk_sha256']}")
    with open(out, "wb") as f:
        f.write(apk)
    print(f"Official CleanYourTV {feed['version_name']} (version code {feed['version_code']}, "
          f"needs Android API {feed['min_sdk']}+), SHA-256 {digest}")


if __name__ == "__main__":
    main()
