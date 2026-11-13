#!/usr/bin/env python3
"""
Собирает app/src/main/assets/debloat_db.json из списков проекта CleanYourTV (MIT License,
https://github.com/Gathin23/cleanyourtv): examples/<модель>/debloat.txt + tv.json.

    git clone --depth 1 https://github.com/Gathin23/cleanyourtv /tmp/cyt
    python3 tools/gen_debloat_db.py /tmp/cyt

Формат строк debloat.txt:
    com.vendor.pkg            # заметка      -> пакет отключён на этой модели (active)
    # com.vendor.pkg          # заметка      -> оставлен по выбору владельца (commented)
"""
import json
import re
import subprocess
import sys
from collections import Counter, defaultdict
from pathlib import Path

PKG = r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+"
ACTIVE_RE = re.compile(rf"^\s*({PKG})\s*(?:#\s*(.*))?$")
COMMENTED_RE = re.compile(rf"^\s*#\s*({PKG})\s*(?:#\s*(.*))?$")

STOP = {
    "android", "google", "smart", "model", "codename", "device", "board", "firmware", "build", "unit",
    "inch", "series", "streaming", "stick", "box", "projector", "and", "the", "with", "sdk", "ram",
    "realtek", "mediatek", "amlogic", "allwinner", "rockchip", "hisilicon", "india", "brazil", "germany",
    "russia", "usa", "us", "uk", "eu", "gen", "1st", "2nd", "3rd", "4th", "edition", "tv",
}


def tokens(text: str) -> set[str]:
    out = set()
    for group in re.findall(r"\(([^)]*)\)", text):
        for t in re.split(r"[,;/\s]+", group):
            t = t.strip().strip(".:").lower()
            if len(t) >= 3 and t not in STOP and re.fullmatch(r"[a-z0-9][a-z0-9_\-]*", t) and not t.isdigit():
                out.add(t)
    return out


def main(repo: Path, out: Path) -> None:
    examples = repo / "examples"
    profiles = []
    stats: dict[str, Counter] = defaultdict(Counter)
    notes: dict[str, Counter] = defaultdict(Counter)

    for folder in sorted(p for p in examples.iterdir() if p.is_dir()):
        lst = folder / "debloat.txt"
        if not lst.exists():
            continue
        meta = {}
        if (folder / "tv.json").exists():
            try:
                meta = json.loads((folder / "tv.json").read_text(encoding="utf-8"))
            except json.JSONDecodeError:
                meta = {}
        lines = lst.read_text(encoding="utf-8", errors="replace").splitlines()
        header = lines[0] if lines else ""
        pkgs = []
        seen = set()
        for line in lines:
            m = ACTIVE_RE.match(line)
            state = 1
            if not m:
                m = COMMENTED_RE.match(line)
                state = 0
            if not m:
                continue
            pkg, note = m.group(1), (m.group(2) or "").strip()
            # отсекаем домены/пути из прозы заголовка
            if pkg in seen or pkg.count(".") < 1 or pkg.split(".")[0] in {"www", "http"}:
                continue
            seen.add(pkg)
            pkgs.append([pkg, state, note[:160]])
            stats[pkg]["a" if state else "c"] += 1
            if note:
                notes[pkg][note[:160]] += 1
        if not pkgs:
            continue
        keys = tokens(meta.get("tv", "")) | tokens(header)
        brand = meta.get("brand") or folder.name.split("-")[0]
        profiles.append({
            "id": folder.name,
            "title": meta.get("tv", folder.name),
            "os": meta.get("os", ""),
            "brand": brand.lower(),
            "keys": sorted(keys),
            "pkgs": pkgs,
        })

    packages = {
        pkg: {"a": c["a"], "c": c["c"], "n": (notes[pkg].most_common(1)[0][0] if notes[pkg] else "")}
        for pkg, c in stats.items()
    }
    try:
        rev = subprocess.check_output(["git", "-C", str(repo), "rev-parse", "--short", "HEAD"], text=True).strip()
    except Exception:
        rev = "unknown"
    db = {
        "source": "CleanYourTV (MIT License) https://github.com/Gathin23/cleanyourtv",
        "revision": rev,
        "profiles": profiles,
        "packages": packages,
    }
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(db, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(f"{len(profiles)} профилей, {len(packages)} пакетов -> {out} ({out.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit("usage: gen_debloat_db.py <path-to-cleanyourtv-checkout> [out.json]")
    repo = Path(sys.argv[1])
    out = Path(sys.argv[2]) if len(sys.argv) > 2 else Path(__file__).resolve().parents[1] / "app/src/main/assets/debloat_db.json"
    main(repo, out)
