"""Lê extensions.txt e prepara a matriz do CI.

Uso: plan.py <extensions.txt> <arquivo $GITHUB_OUTPUT>
Saídas: matrix (JSON: module, gradle, artifact) e paths (um módulo por linha, para o sparse-checkout).
"""
import json
import re
import sys
from pathlib import Path

# O texto vai para comandos do Gradle e para o sparse-checkout: só entra o que tem exatamente a forma src/<lang>/<nome>.
MODULE = re.compile(r"^src/[a-z]{2,3}/[a-z0-9]+$")


def parse_modules(text):
    modules = []
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if not MODULE.fullmatch(line):
            raise ValueError(f"módulo inválido: {line!r} (esperado src/<lang>/<nome>)")
        if line in modules:
            raise ValueError(f"módulo repetido: {line}")
        modules.append(line)
    if not modules:
        raise ValueError("nenhum módulo listado")
    return modules


def build_plan(modules):
    matrix = [
        {"module": m, "gradle": ":" + m.replace("/", ":"), "artifact": m.removeprefix("src/").replace("/", ".") + "-apk"}
        for m in modules
    ]
    return {"matrix": matrix, "paths": modules}


def main(listing, output):
    plan = build_plan(parse_modules(listing.read_text(encoding="utf-8")))
    with output.open("a", encoding="utf-8") as f:
        f.write("matrix=" + json.dumps(plan["matrix"], separators=(",", ":")) + "\n")
        f.write("paths<<EOF_PATHS\n" + "\n".join(plan["paths"]) + "\nEOF_PATHS\n")


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]))
