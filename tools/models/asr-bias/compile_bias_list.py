"""Validate the VI ASR term libraries and compile them for the on-device decoder.

usage: python -X utf8 compile_bias_list.py <asr_gipformer_bpe.model> [out_dir]

Every library listed in libraries.tsv (id, display name, source TSV) is
checked; with out_dir each one is written to <out_dir>/<id>.txt. Stage that
folder as vi-en/asr_gipformer_bias/ (push-npu-models.sh does it from
dist/asr-bias/vi-en/). The app lists whatever libraries it finds there under
their display names, so a new workplace (e.g. "Xưởng nội thất") is a new TSV
plus one row in libraries.tsv, not an app change.

The phone has no sentencepiece, so every spoken variant is tokenized here with
the model's own BPE and written as BPE pieces; the app maps pieces to ids with
asr_gipformer_tokens.txt and fails the load if any piece is unknown (a list
compiled against a different model). Output, one variant per line:

    <boost|->\t<rewrite 0|1>\t<spoken>\t<display>\t<piece piece ...>

then the MT glossary of mt=keep rows, one surface per line:

    g\t<case-sensitive 0|1>\t<surface as Marian would see it>\t<English>

and the EN->VI glossary of vi_mt=keep rows (English form -> Vietnamese display):

    e\t<case-sensitive 0|1>\t<english form>\t<Vietnamese>    (1 for vi_mt=name)

after a header the app reads for its library selector:

    # onevoice-asr-bias v1 source=<tsv> sha256=<16 hex> bpe=<model>
    # id: xuong_may
    # name: Xưởng may
    # terms: 157

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
import re
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
                out.append(display if display is not None else " ".join(words[i:i + n]))
                i += n
                break
        else:
            out.append(words[i])
            i += 1
    return " ".join(out)


HERE = Path(__file__).resolve().parent
LIBRARY_ID = re.compile(r"[a-z0-9_]{1,64}")


def libraries():
    """(id, display name, source TSV path) rows of libraries.tsv."""
    out = []
    for lineno, line in enumerate((HERE / "libraries.tsv").read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip() or line.startswith("#"):
            continue
        cols = [c.strip() for c in line.split("\t")]
        if len(cols) != 3 or not LIBRARY_ID.fullmatch(cols[0]) or not cols[1]:
            sys.exit(f"libraries.tsv line {lineno}: expected <id: a-z 0-9 _> TAB <name> TAB <source.tsv>")
        out.append((cols[0], cols[1], HERE / cols[2]))
    ids = [lib[0] for lib in out]
    if len(set(ids)) != len(ids):
        sys.exit("libraries.tsv: duplicate library id")
    return out


def main():
    if len(sys.argv) not in (2, 3):
        sys.exit(__doc__)
    model = sys.argv[1]
    out_dir = Path(sys.argv[2]) if len(sys.argv) > 2 else None
    sp = spm.SentencePieceProcessor(model_file=model)
    ok = True
    for lib_id, name, tsv in libraries():
        print(f"== {lib_id} ({name}) from {tsv.name}")
        out = out_dir / f"{lib_id}.txt" if out_dir else None
        ok = compile_library(tsv, sp, Path(model).name, lib_id, name, out) and ok
    if not ok:
        sys.exit(1)


def compile_library(tsv, sp, model_name, lib_id, name, out):
    """Checks one library; writes it to out when given. False on any error."""
    unk = sp.unk_id()
    errors, rows, owner = [], [], defaultdict(set)
    by_cat = defaultdict(int)
    glossary = {}  # surface -> (english, line); surfaces kept through MT
    reverse = {}  # lower-case English form -> (Vietnamese display, line), EN->VI
    guards = []

    def gloss(surface, english, lineno):
        surface = " ".join(surface.split())
        if surface in glossary and glossary[surface][0] != english:
            errors.append(f"line {lineno}: '{surface}' has two English terms: "
                          f"'{glossary[surface][0]}' (line {glossary[surface][1]}) and '{english}'")
        glossary.setdefault(surface, (english, lineno))

    for lineno, line in enumerate(tsv.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip() or line.startswith("#"):
            continue
        cols = line.split("\t") + [""] * 8
        cat, display, spoken, en_col, rw, boost, mt, vi_mt = (c.strip() for c in cols[:8])
        # en: the English VI->EN writes first, then other forms an English
        # speaker may say ("line 5 | line five"). "form => Vietnamese" gives a
        # form its own Vietnamese ("brother machine => máy Brother").
        en_forms = []
        for v in en_col.split("|"):
            form, _, vi_override = (" ".join(s.split()) for s in v.partition("=>"))
            if form:
                en_forms.append((form, vi_override))
        en = en_forms[0][0] if en_forms else ""
        if rw == "guard":
            # Phrases a shorter rule must not rewrite ("chuyền năm nay" = this
            # year, not line 5). Kept as spoken; no bias, no glossary.
            for variant in (v.strip() for v in spoken.split("|")):
                if variant:
                    guards.append(" ".join(variant.lower().split()))
            continue
        if rw not in ("yes", "no"):
            errors.append(f"line {lineno}: rewrite must be yes/no/guard, got '{rw}'")
            continue
        if mt not in ("keep", "translate"):
            errors.append(f"line {lineno}: mt must be keep/translate, got '{mt}'")
            continue
        if mt == "keep" and not en:
            errors.append(f"line {lineno}: mt=keep needs the English term in column en")
            continue
        if vi_mt not in ("keep", "name", "translate"):
            errors.append(f"line {lineno}: vi_mt must be keep/name/translate, got '{vi_mt}'")
            continue
        if vi_mt in ("keep", "name"):
            # EN->VI: an English speaker's term becomes the Vietnamese display.
            # A `name` matches only as capitalised ("Brother" the brand), so
            # the everyday word ("my brother") is still Marian's. Parakeet often
            # writes brands in lower case, so a name row also lists context
            # forms with their own Vietnamese ("brother machine => máy Brother"),
            # and those match whatever the case.
            for form, vi_override in en_forms:
                vietnamese = vi_override or display
                key = form if vi_mt == "name" and not vi_override else form.lower()
                if key in reverse and reverse[key][0] != vietnamese:
                    errors.append(f"line {lineno}: English '{form}' has two Vietnamese terms: "
                                  f"'{reverse[key][0]}' (line {reverse[key][1]}) and '{vietnamese}'")
                reverse.setdefault(key, (vietnamese, lineno))
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
            if mt == "keep":
                # What reaches Marian: the display form after a rewrite (an
                # override that does not contain the row's display adds its
                # own surface, e.g. "Garco 10"), else the words as spoken.
                if rw == "no":
                    gloss(said, en, lineno)
                elif f" {display} " not in f" {shown} ":
                    gloss(shown, en, lineno)
        if mt == "keep":
            # The display is the term itself, also for rewrite=no rows whose
            # spoken forms are longer: "chuyền" alone ("cuối chuyền") must not
            # reach Marian ("the end of the necklace") just because the row
            # lists "chuyền may" as its spoken form.
            gloss(display, en, lineno)

    for said, shown in owner.items():
        if len(shown) > 1:
            errors.append(f"'{said}' maps to several outputs: {sorted(shown)}")

    rules = defaultdict(list)
    for _, _, rw, said, shown, _ in rows:
        if rw and said != shown:
            words = said.split()
            rules[words[0]].append((words, shown))
    for guard in guards:
        words = guard.split()
        rules[words[0]].append((words, None))
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
    print(f"{sum(by_cat.values())} terms, {len(rows)} spoken variants, {n_rewrite} rewrite rules, "
          f"{len(glossary)} glossary surfaces, {len(reverse)} English->Vietnamese terms")
    print("  " + ", ".join(f"{c}={n}" for c, n in sorted(by_cat.items())))
    for e in errors:
        print("ERROR", e)
    if errors:
        return False

    if out:
        digest = hashlib.sha256(tsv.read_bytes()).hexdigest()[:16]
        lines = [
            f"# onevoice-asr-bias v1 source={tsv.name} sha256={digest} bpe={model_name}",
            f"# id: {lib_id}",
            f"# name: {name}",
            f"# terms: {sum(by_cat.values())}",
        ]
        for _, boost, rw, said, shown, pieces in rows:
            lines.append("\t".join([boost, "1" if rw else "0", said, shown, " ".join(pieces)]))
        # Guards: k <TAB> <words>, kept as spoken by the app's rewrite.
        for guard in guards:
            lines.append("\t".join(["k", guard]))
        # MT glossary: g <TAB> <case-sensitive 0|1> <TAB> <surface> <TAB> <English>.
        # A surface with a capital only matches as written, so the ordinary
        # words "thành công" never take the company name's English.
        for surface, (english, _) in sorted(glossary.items()):
            cased = "1" if surface != surface.lower() else "0"
            lines.append("\t".join(["g", cased, surface, english]))
        # EN->VI glossary: e <TAB> <case-sensitive 0|1> <TAB> <English form> <TAB> <Vietnamese>;
        # keep forms match any case (Parakeet capitalises sentence starts), names only as written.
        for surface, (vietnamese, _) in sorted(reverse.items()):
            cased = "1" if surface != surface.lower() else "0"
            lines.append("\t".join(["e", cased, surface, vietnamese]))
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
        print(f"wrote {out} ({len(rows)} entries)")
    return True


if __name__ == "__main__":
    main()
