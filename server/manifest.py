#!/usr/bin/env python3
"""Maintains manifest.json, Tally's Jellyfin plugin repository.

Jellyfin servers that list https://raw.githubusercontent.com/Scdouglas1999/Tally/main/server/manifest.json under
Dashboard > Plugins > Repositories (the plugin adds it by itself) install and update Tally from it. Each release adds
one entry per plugin build; the entries point at the zips attached to that GitHub release.

    manifest.py add manifest.json --tag tally-v2.0.0 dist/Tally-server-2.0.0-jf10.10.zip dist/Tally-server-2.0.0-jf12.zip

Version, targetAbi, timestamp and changelog come from the meta.json inside each zip; the checksum is the zip's MD5.
Entries are sorted newest first. Each build has its own version (2.0.0.10, 2.0.0.11, 2.0.0.12 for the Jellyfin 10.10,
10.11 and 12 builds), so a Jellyfin 12 server, which accepts all three, takes the 12 build as the newest, and a server
upgraded from 10.10 to 12 is moved from 2.0.0.10 to 2.0.0.12 by Jellyfin's own plugin updates.
"""
import argparse
import hashlib
import json
import os
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
RELEASES = "https://github.com/Scdouglas1999/Tally/releases/download"
IMAGE = "https://raw.githubusercontent.com/Scdouglas1999/Tally/main/server/icon.png"


def version_key(text):
    return tuple(int(p) for p in text.split("."))


def package_header():
    meta = json.load(open(os.path.join(HERE, "meta.template.json")))
    return {
        "guid": meta["guid"],
        "name": meta["name"],
        "description": meta["description"],
        "overview": meta["overview"],
        "owner": meta["owner"],
        "category": meta["category"],
        "imageUrl": IMAGE,
    }


def load(path):
    if os.path.exists(path):
        with open(path) as f:
            return json.load(f)
    return [dict(package_header(), versions=[])]


def entry_for(zip_path, tag, base_url):
    with zipfile.ZipFile(zip_path) as z:
        meta = json.loads(z.read("meta.json"))
    with open(zip_path, "rb") as f:
        md5 = hashlib.md5(f.read()).hexdigest()
    abi = ".".join(meta["targetAbi"].split(".")[:2]).removesuffix(".0")
    changelog = (meta.get("changelog") or "").strip()
    # a Jellyfin 12 server lists both builds of a version; the note tells them apart
    changelog = f"{changelog}\n\nBuild for Jellyfin {abi}." if changelog else f"Build for Jellyfin {abi}."
    return {
        "version": meta["version"],
        "changelog": changelog,
        "targetAbi": meta["targetAbi"],
        "sourceUrl": f"{base_url.rstrip('/')}/{tag}/{os.path.basename(zip_path)}",
        "checksum": md5,
        "timestamp": meta["timestamp"],
    }


def add(args):
    manifest = load(args.manifest)
    header = package_header()
    package = next((p for p in manifest if p.get("guid") == header["guid"]), None)
    if package is None:
        package = dict(header, versions=[])
        manifest.append(package)
    versions = package.pop("versions", [])
    package.update(header)
    for zip_path in args.zips:
        new = entry_for(zip_path, args.tag, args.base_url)
        versions = [v for v in versions if (v["version"], v["targetAbi"]) != (new["version"], new["targetAbi"])]
        versions.append(new)
        print(f"{new['version']} for Jellyfin {new['targetAbi']}: {new['sourceUrl']} md5 {new['checksum']}")
    versions.sort(key=lambda v: (version_key(v["version"]), version_key(v["targetAbi"])), reverse=True)
    package["versions"] = versions
    out = args.out or args.manifest
    with open(out, "w") as f:
        json.dump(manifest, f, indent=2)
        f.write("\n")
    print(f"wrote {out}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    a = sub.add_parser("add", help="add (or replace) the entries for a release's plugin zips")
    a.add_argument("manifest")
    a.add_argument("zips", nargs="+")
    a.add_argument("--tag", required=True, help="the GitHub release tag the zips are attached to")
    a.add_argument("--base-url", default=RELEASES, help="where release downloads live (a local server for tests)")
    a.add_argument("--out", help="write here instead of over the manifest")
    args = parser.parse_args()
    if args.command == "add":
        add(args)


if __name__ == "__main__":
    sys.exit(main())
