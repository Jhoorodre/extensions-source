"""Atualiza um repositório de extensões no formato legado do Mihon (repo.json + index.min.json + apk/).

Uso: publish.py <pasta de artefatos> <pasta do repositório publicado>
Cada subpasta de artefatos é uma extensão, com o keiyoushi-source-info.json, o APK e o ic_launcher.png.
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


def extension_dirs(artifacts):
    if not artifacts.is_dir():
        return []
    return sorted(d for d in artifacts.iterdir() if d.is_dir() and any(d.glob("**/keiyoushi-source-info.json")))


def single_fingerprint(fingerprints):
    unique = set(fingerprints)
    if len(unique) != 1:
        raise ValueError(f"as extensões precisam da mesma chave de assinatura, achei: {sorted(unique)}")
    return unique.pop()


def read_extension(folder):
    info = json.loads(next(folder.glob("**/keiyoushi-source-info.json")).read_text(encoding="utf-8"))
    apk = next(folder.glob("**/*.apk"), None)
    if apk is None:
        raise FileNotFoundError(f"{folder.name}: nenhum APK no artefato")
    return info, apk, next(folder.glob("**/ic_launcher.png"), None)


def apksigner_fingerprint(apk):
    result = subprocess.run([find_apksigner(), "verify", "--print-certs", str(apk)], capture_output=True, text=True, check=True)
    return parse_fingerprint(result.stdout)


def main(artifacts, out, fingerprint_of=apksigner_fingerprint):
    folders = extension_dirs(artifacts)
    if not folders:
        raise FileNotFoundError(f"nenhum artefato de extensão em {artifacts}")
    # Tudo é lido e conferido antes de escrever: uma extensão com problema não publica nada.
    extensions = [read_extension(folder) for folder in folders]
    fingerprint = single_fingerprint([fingerprint_of(apk) for _, apk, _ in extensions])

    apk_dir = out / "apk"
    apk_dir.mkdir(parents=True, exist_ok=True)
    index_file = out / "index.min.json"
    entries = json.loads(index_file.read_text(encoding="utf-8")) if index_file.exists() else []
    for info, apk, icon in extensions:
        module = info["packageName"].removeprefix("eu.kanade.tachiyomi.extension.")
        for old in apk_dir.glob(f"tachiyomi-{module}-*.apk"):
            old.unlink()
        (apk_dir / apk.name).write_bytes(apk.read_bytes())
        if icon:
            (out / "icon").mkdir(exist_ok=True)
            (out / "icon" / f"{info['packageName']}.png").write_bytes(icon.read_bytes())
        entries = merge_index(entries, make_entry(info, apk.name))
    index_file.write_text(json.dumps(entries, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")

    meta = {"name": "Nox Extensions", "shortName": "NOX", "website": WEBSITE, "signingKeyFingerprint": fingerprint}
    (out / "repo.json").write_text(json.dumps({"meta": meta}, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]))
