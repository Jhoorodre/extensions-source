"""Atualiza um repositório de extensões no formato legado do Mihon (repo.json + index.min.json + apk/).

Uso: publish.py <pasta com o APK e o keiyoushi-source-info.json> <pasta do repositório publicado>
"""
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

WEBSITE = "https://github.com/Jhoorodre/extensions-source"
NSFW = 3  # ContentWarning do index.proto: 1 SAFE, 2 MIXED, 3 NSFW; o formato legado só tem nsfw 0/1.


def make_entry(info, apk_name):
    langs = {s["lang"] for s in info["sources"]}
    return {
        "name": info["name"],
        "pkg": info["packageName"],
        "apk": apk_name,
        "lang": langs.pop() if len(langs) == 1 else "all",
        "code": info["versionCode"],
        "version": info["versionName"],
        "nsfw": 1 if info["contentWarning"] == NSFW else 0,
        "sources": [
            {"id": int(s["id"]), "lang": s["lang"], "name": s["name"], "baseUrl": s["baseUrl"]}
            for s in info["sources"]
        ],
    }


def parse_fingerprint(apksigner_output):
    match = re.search(r"certificate SHA-256 digest:\s*([0-9a-f]{64})", apksigner_output)
    if not match:
        raise ValueError("SHA-256 do certificado não encontrado na saída do apksigner")
    return match.group(1)


def find_apksigner():
    found = shutil.which("apksigner")
    if found:
        return found
    candidates = sorted(Path(os.environ["ANDROID_HOME"]).glob("build-tools/*/apksigner"))
    if not candidates:
        raise FileNotFoundError("apksigner não encontrado (defina ANDROID_HOME)")
    return str(candidates[-1])


def merge_index(existing, entry):
    return sorted([e for e in existing if e["pkg"] != entry["pkg"]] + [entry], key=lambda e: e["pkg"])


def main(artifacts, out):
    info = json.loads(next(artifacts.glob("**/keiyoushi-source-info.json")).read_text(encoding="utf-8"))
    apk = next(artifacts.glob("**/*.apk"))
    fingerprint = parse_fingerprint(
        subprocess.run([find_apksigner(), "verify", "--print-certs", str(apk)], capture_output=True, text=True, check=True).stdout
    )

    apk_dir = out / "apk"
    apk_dir.mkdir(parents=True, exist_ok=True)
    module = info["packageName"].removeprefix("eu.kanade.tachiyomi.extension.")
    for old in apk_dir.glob(f"tachiyomi-{module}-*.apk"):
        old.unlink()
    (apk_dir / apk.name).write_bytes(apk.read_bytes())

    index_file = out / "index.min.json"
    existing = json.loads(index_file.read_text(encoding="utf-8")) if index_file.exists() else []
    entries = merge_index(existing, make_entry(info, apk.name))
    index_file.write_text(json.dumps(entries, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")

    meta = {"name": "Nox Extensions", "shortName": "NOX", "website": WEBSITE, "signingKeyFingerprint": fingerprint}
    (out / "repo.json").write_text(json.dumps({"meta": meta}, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]))
