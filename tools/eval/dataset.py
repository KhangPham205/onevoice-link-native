"""Organisers' Round 3 sample dataset: wav files plus an xlsx of references.

Layout (as distributed)::

    Vie-Eng/OneVoice_V_to_E_samples_100.xlsx   ID | Tiếng Việt | Tiếng Anh - Final
    Vie-Eng/wav/VE-xxxx.wav
    Eng-Vie/OneVoice_E_to_V_samples_100.xlsx   ID | Tiếng Anh (normalized) | Tiếng Việt (normalized)
    Eng-Vie/wav/EV-xxxx.wav
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import openpyxl


@dataclass(frozen=True)
class Item:
    id: str
    source_lang: str  # "vi" or "en": the language spoken in the wav
    source_text: str
    target_text: str
    wav: Path


def load_items(root: Path, limit: int = 0) -> list[Item]:
    items: list[Item] = []
    for folder, lang in (("Vie-Eng", "vi"), ("Eng-Vie", "en")):
        sheets = sorted((root / folder).glob("*.xlsx"))
        if len(sheets) != 1:
            raise FileNotFoundError(f"expected one xlsx in {root / folder}, found {sheets}")
        rows = list(openpyxl.load_workbook(sheets[0], read_only=True).active.iter_rows(values_only=True))
        taken = 0
        for row in rows[1:]:
            if not row or not row[0]:
                continue
            wav = root / folder / "wav" / f"{row[0]}.wav"
            if not wav.is_file():
                raise FileNotFoundError(wav)
            items.append(Item(str(row[0]), lang, str(row[1]).strip(), str(row[2]).strip(), wav))
            taken += 1
            if limit and taken >= limit:
                break
    return items
