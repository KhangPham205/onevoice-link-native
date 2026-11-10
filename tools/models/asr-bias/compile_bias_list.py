"""Validate a VI ASR bias list and compile it for the on-device RNN-T decoder.

usage: python -X utf8 compile_bias_list.py <bias.tsv> <asr_gipformer_bpe.model> [out.txt]

The phone has no sentencepiece, so every spoken variant is tokenized here with
the model's own BPE and written as BPE pieces; the app maps pieces to ids with
asr_gipformer_tokens.txt and fails the load if any piece is unknown (a list
compiled against a different model). Output, one variant per line:

    <boost|->\t<rewrite 0|1>\t<spoken>\t<display>\t<piece piece ...>

The compile fails (exit 1) on:
  * a variant that encodes to <unk>: the decoder can never emit it;
  * a variant of one BPE piece: one common token would collect the bonus on
    every occurrence, which is how over-biasing inserts terms into speech;
  * one spoken form mapped to two display forms;
  * a rewrite that is not idempotent. The app rewrites the decoder transcript
    and again at MT input (long turns rebuild text from tokens), so rewriting
    an already rewritten text must change nothing.
"""
import hashlib
import sys
from collections import defaultdict
from pathlib import Path

import sentencepiece as spm


def rewrite(text, rules):
    """Mirror of AsrBiasList.rewrite: left-to-right, longest match, whole words."""
    words = text.split()
    out, i = [], 0
    while i < len(words):
        for spoken, display in rules.get(words[i].lower(), ()):
            n = len(spoken)
            if [w.lower() for w in words[i:i + n]] == spoken:
                out.append(display)
                i += n
                break
        else:
            out.append(words[i])
            i += 1
    return " ".join(out)


def main():
    tsv, model = Path(sys.argv[1]), sys.argv[2]
    out = Path(sys.argv[3]) if len(sys.argv) > 3 else None
    sp = spm.SentencePieceProcessor(model_file=model)
    unk = sp.unk_id()
    errors, rows, owner = [], [], defaultdict(set)
    by_cat = defaultdict(int)

    for lineno, line in enumerate(tsv.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip() or line.startswith("#"):
            continue
        cols = line.split("\t") + [""] * 6
        cat, display, spoken, _en, rw, boost = (c.strip() for c in cols[:6])
        if rw not in ("yes", "no"):
            errors.append(f"line {lineno}: rewrite must be yes/no, got '{rw}'")
            continue
        if boost:
            try:
                assert float(boost) > 0
            except (ValueError, AssertionError):
                errors.append(f"line {lineno}: boost must be a positive number, got '{boost}'")
                continue
        by_cat[cat] += 1
        for variant in (v.strip() for v in spoken.split("|")):
            if not variant:
                continue
            said, _, override = (s.strip() for s in variant.partition("=>"))
            said = " ".join(said.lower().split())
            shown = override or display
            ids = sp.encode(said.upper())  # GIPFormer vocabulary is upper-case
            pieces = [sp.id_to_piece(i) for i in ids]
            if unk in ids:
                errors.append(f"line {lineno}: '{said}' -> <unk> in {pieces}")
            elif len(ids) < 2:
                errors.append(f"line {lineno}: '{said}' is one piece {pieces}: over-bias risk")
            owner[said].add(shown if rw == "yes" else said)
            rows.append((lineno, boost or "-", rw == "yes", said, shown, pieces))

    for said, shown in owner.items():
        if len(shown) > 1:
            errors.append(f"'{said}' maps to several outputs: {sorted(shown)}")

    rules = defaultdict(list)
    for _, _, rw, said, shown, _ in rows:
        if rw and said != shown:
            words = said.split()
            rules[words[0]].append((words, shown))
    for lst in rules.values():
        lst.sort(key=lambda r: -len(r[0]))
    for lineno, _, rw, said, shown, _ in rows:
        if not rw:
            continue
        if rewrite(said, rules) != shown:
            errors.append(f"line {lineno}: '{said}' rewrites to '{rewrite(said, rules)}', not '{shown}'")
        if rewrite(shown, rules) != shown:
            errors.append(f"line {lineno}: display '{shown}' is rewritten again to '{rewrite(shown, rules)}'")

    n_rewrite = sum(1 for r in rows if r[2] and r[3] != r[4])
    print(f"{sum(by_cat.values())} terms, {len(rows)} spoken variants, {n_rewrite} rewrite rules")
    print("  " + ", ".join(f"{c}={n}" for c, n in sorted(by_cat.items())))
    for e in errors:
        print("ERROR", e)
    if errors:
        sys.exit(1)

    if out:
        digest = hashlib.sha256(tsv.read_bytes()).hexdigest()[:16]
        lines = [f"# onevoice-asr-bias v1 source={tsv.name} sha256={digest} bpe={Path(model).name}"]
        for _, boost, rw, said, shown, pieces in rows:
            lines.append("\t".join([boost, "1" if rw else "0", said, shown, " ".join(pieces)]))
        out.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
        print(f"wrote {out} ({len(rows)} entries)")


if __name__ == "__main__":
    main()
