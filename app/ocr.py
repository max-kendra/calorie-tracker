"""
OCR extraction for nutrition labels.

Flow: run Tesseract across all supported languages combined -> pick the
best-matching language by counting keyword hits per language's dictionary
-> parse per-100g macro values from the recognized text using that
language's keyword patterns -> convert salt->sodium if the label uses
salt (EU convention) rather than sodium directly.

This NEVER writes to our DB -- same pattern as USDA and barcode scanning:
the result is a best-effort draft for the client to show in the Add Item
review form, which the user corrects before anything is saved.

ENGINE NOTE (Tesseract branch): this app has gone back and forth between
Tesseract (pytesseract) and EasyOCR -- see the other branch/history for
that story. This branch is Tesseract again, to actually compare the two
on real data (see app/ocr_metrics.py, which records per-scan timing/
memory tagged by `engine` for exactly this). Tesseract is a classical
(non-neural-network) OCR engine -- much lighter on CPU-only hardware
than EasyOCR/PyTorch, and its language data is installed via apt at
image-build time (see Dockerfile), not downloaded lazily on first use --
so there's no equivalent of EasyOCR's first-request download/timeout
issue here at all, and no Reader-construction/warm-up step really
needed (see warm_up() below, now a no-op).

Tesseract also doesn't have EasyOCR's "which languages can share one
model" restriction (that's what forced trimming down to 4 languages on
the EasyOCR branch) -- each Tesseract language is its own independent
traineddata file, and combining several via lang="eng+dan+..." doesn't
raise on an unsupported combination the way EasyOCR's Reader did. The
tradeoff is speed, not compatibility: more combined languages means
Tesseract tries recognition against more trained models, which does
scale roughly with how many are listed -- worth keeping an eye on via
ocr_metrics.py if language coverage gets expanded further.

The parsing/keyword logic below (`_find_keyword_anchor`,
`_find_number_to_right`, `LANGUAGE_CONFIGS`, etc.) is engine-agnostic --
it just operates on a list of recognized words and their positions,
regardless of which OCR engine produced them. Reading order/reassembly
IS still done (see _reconstruct_reading_order), but ONLY for the
raw_text field kept for display/debugging and for detect_language()
(position-insensitive keyword counting) -- actual macro extraction
matches each keyword directly against nearby word positions instead of
reassembling everything into lines of text first and parsing within a
line. That line-based approach was tried on both EasyOCR and Tesseract
and failed differently each time -- see parse_label's doc comment for
the full story of why this rewrite happened.

HONESTY ABOUT LANGUAGE COVERAGE: Danish, German, and English keyword
dictionaries below were verified against real Tesseract OCR output on
synthetic label images (see tests). Swedish, Finnish, Norwegian,
Spanish, Slovak, Czech, and Polish dictionaries use standard EU
nutrition-label vocabulary (consistent under EU FIC labeling regulation)
but have NOT been run through real OCR here -- treat them as a
reasonable starting point, not verified, until tested against real
labels in those languages.
"""

import difflib
import logging
import re
from dataclasses import dataclass, field
from decimal import Decimal
from io import BytesIO
from typing import Optional

import pytesseract
from PIL import Image

from app.nutrition import salt_g_to_sodium_mg

logger = logging.getLogger(__name__)

# Longest-side cap for run_ocr()'s pre-resize. 1600px keeps printed
# nutrition-label text comfortably legible (these aren't small-print
# ingredient lists needing max resolution) while cutting CPU inference
# time on constrained hardware -- Tesseract is much lighter than EasyOCR
# to begin with, but this still helps and costs nothing to keep. Not
# benchmarked against a specific "too small, text becomes unreadable"
# threshold -- worth revisiting if recognition quality drops noticeably.
_MAX_OCR_DIMENSION = 1600

# Tesseract's own multi-language syntax (lang="a+b+c") -- these are
# Tesseract's native 3-letter codes, which is ALSO what LANGUAGE_CONFIGS
# below is keyed by, so unlike the EasyOCR branch there's no separate
# code-translation map needed here at all. Each of these needs its
# corresponding `tesseract-ocr-<code>` apt package installed (see
# Dockerfile) -- pytesseract itself does nothing without that data
# present on the system.
#
# Not restricted to 4 languages the way the EasyOCR branch was -- that
# restriction existed specifically because EasyOCR couldn't combine
# certain languages into one Reader (raised ValueError for Finnish
# specifically, confirmed against a real error). Tesseract doesn't have
# that restriction, so Finnish is back, and there's no reason not to use
# the full set this app has ever supported.
_TESSERACT_LANGS = "eng+dan+deu+swe+fin+nor+spa+slk+ces+pol"


def warm_up():
    """
    No-op on this engine. EasyOCR needed this to force its one-time model
    download/Reader construction to happen at startup instead of on
    whichever live request happened to be first (see that branch's
    history for the timeout bug this fixed). Tesseract has no equivalent
    cost -- its language data is installed via apt at image-build time,
    already on disk and ready the moment the container starts, nothing
    to lazily initialize. Kept as a function (even though it does
    nothing here) so app/main.py's startup hook doesn't need to change
    between branches -- it becomes meaningful again automatically if you
    switch back to EasyOCR.
    """
    pass


@dataclass
class LabelLanguageConfig:
    per_100g_markers: list[str]
    energy_keyword: str  # matched directly before "kcal"
    fat_keyword: str
    fat_exclude_prefixes: list[str]
    saturated_fat_keyword: str
    carbs_keyword: str
    carbs_exclude_prefixes: list[str]
    sugar_keyword: str
    fiber_keyword: str
    protein_keyword: str
    # Both optional -- most labels only show one or the other. salt_keyword
    # happens to be provided by every LANGUAGE_CONFIGS entry below in
    # practice, but is still guarded with `if config.salt_keyword:`
    # wherever it's read, same as sodium_keyword, so a future language
    # entry is free to omit either.
    salt_keyword: Optional[str] = None
    sodium_keyword: Optional[str] = None


LANGUAGE_CONFIGS: dict[str, LabelLanguageConfig] = {
    "dan": LabelLanguageConfig(
        per_100g_markers=["pr. 100 g", "pr 100 g", "100 g"],
        energy_keyword="energi",
        fat_keyword="fedt",
        fat_exclude_prefixes=["heraf"],
        saturated_fat_keyword="mættede fedtsyrer",
        carbs_keyword="kulhydrat",
        carbs_exclude_prefixes=["heraf"],
        sugar_keyword="sukkerarter",
        fiber_keyword="kostfibre",
        protein_keyword="protein",
        salt_keyword="salt",
    ),
    "deu": LabelLanguageConfig(
        per_100g_markers=["je 100 g", "pro 100 g", "100 g"],
        energy_keyword="brennwert",
        fat_keyword="fett",
        fat_exclude_prefixes=["davon"],
        saturated_fat_keyword="gesättigte fettsäuren",
        carbs_keyword="kohlenhydrate",
        carbs_exclude_prefixes=["davon"],
        sugar_keyword="zucker",
        fiber_keyword="ballaststoffe",
        # Tolerates "eiweiß"/"eiweiss" (standard spelling variant) and
        # "eiweib"/"eiweis" -- found via real Tesseract testing that the
        # deu model can misread the ß character as a capital B.
        protein_keyword=r"eiwei(ß|ss|b|s)",
        salt_keyword="salz",
    ),
    "eng": LabelLanguageConfig(
        per_100g_markers=["per 100 g", "per 100g"],
        energy_keyword="energy",
        fat_keyword="fat",
        fat_exclude_prefixes=["of which", "saturates"],
        saturated_fat_keyword="saturates",
        carbs_keyword="carbohydrate",
        carbs_exclude_prefixes=["of which"],
        sugar_keyword="sugars",
        fiber_keyword="fibre",
        protein_keyword="protein",
        sodium_keyword="sodium",
        salt_keyword="salt",  # UK labels sometimes show both
    ),
    "swe": LabelLanguageConfig(
        per_100g_markers=["per 100 g", "100 g"],
        energy_keyword="energi",
        fat_keyword="fett",
        fat_exclude_prefixes=["varav"],
        saturated_fat_keyword="mättat fett",
        carbs_keyword="kolhydrat",
        carbs_exclude_prefixes=["varav"],
        sugar_keyword="sockerarter",
        fiber_keyword="fibrer",
        protein_keyword="protein",
        salt_keyword="salt",
    ),
    # Re-added on this branch -- removed on the EasyOCR branch because
    # EasyOCR's Reader specifically raised ValueError trying to combine
    # Finnish with the other languages (confirmed against a real error).
    # Tesseract has no such restriction (see _TESSERACT_LANGS' doc
    # comment), so there's no reason to leave this out here.
    "fin": LabelLanguageConfig(
        per_100g_markers=["100 g"],
        energy_keyword="energia",
        fat_keyword="rasva",
        fat_exclude_prefixes=["josta"],
        saturated_fat_keyword="tyydyttynyttä",
        carbs_keyword="hiilihydraatti",
        carbs_exclude_prefixes=["josta"],
        sugar_keyword="sokereita",
        fiber_keyword="kuitu",
        protein_keyword="proteiini",
        salt_keyword="suola",
    ),
    "nor": LabelLanguageConfig(
        per_100g_markers=["100 g"],
        energy_keyword="energi",
        fat_keyword="fett",
        fat_exclude_prefixes=["hvorav"],
        saturated_fat_keyword="mettet fett",
        carbs_keyword="karbohydrat",
        carbs_exclude_prefixes=["hvorav"],
        sugar_keyword="sukkerarter",
        fiber_keyword="fiber",
        protein_keyword="protein",
        salt_keyword="salt",
    ),
    "spa": LabelLanguageConfig(
        per_100g_markers=["100 g"],
        energy_keyword="energía",
        fat_keyword="grasas",
        fat_exclude_prefixes=["de las cuales", "de los cuales"],
        saturated_fat_keyword="saturadas",
        carbs_keyword="hidratos de carbono",
        carbs_exclude_prefixes=["de las cuales", "de los cuales"],
        sugar_keyword="azúcares",
        fiber_keyword="fibra",
        protein_keyword="proteínas",
        salt_keyword="sal",
    ),
    "slk": LabelLanguageConfig(
        per_100g_markers=["100 g"],
        energy_keyword="energetická hodnota",
        fat_keyword="tuky",
        fat_exclude_prefixes=["z toho"],
        saturated_fat_keyword="nasýtené mastné kyseliny",
        carbs_keyword="sacharidy",
        carbs_exclude_prefixes=["z toho"],
        sugar_keyword="cukry",
        fiber_keyword="vláknina",
        protein_keyword="bielkoviny",
        salt_keyword="soľ",
    ),
    "ces": LabelLanguageConfig(
        per_100g_markers=["100 g"],
        energy_keyword="energetická hodnota",
        fat_keyword="tuky",
        fat_exclude_prefixes=["z toho"],
        saturated_fat_keyword="nasycené mastné kyseliny",
        carbs_keyword="sacharidy",
        carbs_exclude_prefixes=["z toho"],
        sugar_keyword="cukry",
        fiber_keyword="vláknina",
        protein_keyword="bílkoviny",
        salt_keyword="sůl",
    ),
    # Was missing entirely -- "pol" was already in the OCR reader's
    # language list (both before and after the 9->4 language trim) but
    # had no keyword dictionary to actually parse recognized Polish text
    # against, so a Polish label could get read by EasyOCR just fine and
    # still never produce any macros. Found while double-checking the
    # language trim per design discussion.
    "pol": LabelLanguageConfig(
        per_100g_markers=["100 g"],
        energy_keyword="wartość energetyczna",
        fat_keyword="tłuszcz",
        fat_exclude_prefixes=["w tym"],
        saturated_fat_keyword="kwasy tłuszczowe nasycone",
        carbs_keyword="węglowodany",
        carbs_exclude_prefixes=["w tym"],
        sugar_keyword="cukry",
        fiber_keyword="błonnik",
        protein_keyword="białko",
        salt_keyword="sól",
    ),
}


@dataclass
class OcrExtractionResult:
    raw_text: str
    detected_language: Optional[str]
    per_100g_confirmed: bool  # whether a "per 100g" marker was actually found
    macros: dict = field(default_factory=dict)


@dataclass
class OcrWord:
    """One recognized word + its position, from Tesseract's image_to_data.
    Used directly for geometric keyword-to-value matching in parse_label
    (see that function's doc comment for why) -- NOT reassembled into
    lines of text before parsing, unlike the raw_text field kept on
    OcrExtractionResult purely for display/debugging."""
    text: str
    x_left: float
    x_right: float
    y_center: float
    height: float


def _fuzzy_contains(line_lower: str, keyword: str, threshold: float = 0.8) -> bool:
    """
    Fuzzy alternative to `keyword in line_lower` -- tolerates the kind of
    single/double-character OCR misreads seen in practice (e.g. "fedt"
    read as "feot", confirmed against real scan output: NÆRINGSOPLYSNINGER
    itself came back as multiple different garbled spellings across
    lines). An exact substring match would silently reject a keyword
    that WAS effectively there, just misread by one character -- which
    is indistinguishable from the keyword genuinely not being present,
    so real labels were failing to parse for no visible reason.

    Performance-wise this is negligible -- comparing a handful of short
    OCR'd lines against a handful of short keywords is microseconds of
    work, dwarfed by the OCR inference itself (multiple SECONDS). Not
    something worth trading accuracy for.

    Deliberately NOT used for the per_100g_markers check in parse_label()
    -- those markers are numeric ("100 g"), and fuzzy-matching digits is
    a different, riskier kind of tolerance than fuzzy-matching word-based
    labels (a misread "700g" fuzzy-matching "100g" would be silently
    WRONG, not just imprecise -- unlike two spellings of the same word,
    differing digits usually mean a genuinely different number).
    """
    if keyword in line_lower:
        return True  # cheap common case -- no need to fuzzy-match if this already matches

    # Slide a same-length window across the line and compare each to the
    # keyword -- handles the keyword appearing as a substring within a
    # longer line (e.g. "feot 53,7g"), not just the whole line being
    # (roughly) equal to the keyword alone.
    n = len(keyword)
    if n == 0 or len(line_lower) < n:
        return difflib.SequenceMatcher(None, line_lower, keyword).ratio() >= threshold

    best = 0.0
    for start in range(0, len(line_lower) - n + 1):
        window = line_lower[start:start + n]
        ratio = difflib.SequenceMatcher(None, window, keyword).ratio()
        if ratio > best:
            best = ratio
        if best >= threshold:
            return True
    return best >= threshold


def _score_text_for_language(text_lower: str, config: "LabelLanguageConfig") -> int:
    """How many of this language's macro keywords show up in the text --
    used by detect_language() below to pick the best-matching language
    for a single shared OCR pass. Fuzzy now (see _fuzzy_contains) -- this
    runs BEFORE parse_label() even knows which language it's dealing
    with, so a misread keyword here could pick the wrong language (or no
    language at all) before parsing ever gets a chance."""
    keywords = [
        config.energy_keyword,
        config.fat_keyword,
        config.saturated_fat_keyword,
        config.carbs_keyword,
        config.sugar_keyword,
        config.fiber_keyword,
        config.protein_keyword,
    ]
    if config.salt_keyword:
        keywords.append(config.salt_keyword)
    return sum(1 for kw in keywords if _fuzzy_contains(text_lower, kw))


def _words_from_tesseract_data(data: dict) -> list["OcrWord"]:
    """Flattens Tesseract's image_to_data dict (parallel lists) into a
    list of OcrWord, dropping blank entries (block/paragraph/line marker
    rows that image_to_data includes alongside actual recognized words,
    which have empty text)."""
    words = []
    n = len(data.get("text", []))
    for i in range(n):
        text = data["text"][i].strip()
        if not text:
            continue
        left = data["left"][i]
        top = data["top"][i]
        width = data["width"][i]
        height = data["height"][i]
        words.append(OcrWord(
            text=text,
            x_left=left,
            x_right=left + width,
            y_center=top + height / 2,
            height=height,
        ))
    return words


def _reconstruct_reading_order(words: list["OcrWord"]) -> str:
    """
    Reassembles words into a display/debug text string ONLY -- this is
    NOT used for actual macro extraction anymore (see parse_label's doc
    comment for why). Still useful for OcrExtractionResult.raw_text
    (shown to the user, logged) and for detect_language(), which just
    counts whether keywords appear ANYWHERE in the text and doesn't
    care about row alignment -- so imperfect reconstruction here doesn't
    hurt language detection the way it hurt direct parsing.

    Groups words into rows via gap-based splitting (sort top-to-bottom,
    start a new row when the gap to the previous word exceeds a
    threshold based on that PAIR's own average height) -- see git
    history on this function for two earlier, buggier clustering
    attempts (a drift-prone running-average approach, then a
    global-median-height threshold that still mis-clustered a boundary
    next to smaller subordinate-line text). This version is kept
    because it's still reasonable for display purposes, not because
    it's assumed correct enough to parse against -- that assumption is
    exactly what kept failing.
    """
    if not words:
        return ""

    items = sorted(words, key=lambda w: w.y_center)

    rows: list[list["OcrWord"]] = [[items[0]]]
    for prev, curr in zip(items, items[1:]):
        gap = curr.y_center - prev.y_center
        pair_height = (prev.height + curr.height) / 2
        row_gap_threshold = max(pair_height, 1) * 0.6
        if gap > row_gap_threshold:
            rows.append([curr])
        else:
            rows[-1].append(curr)

    lines = []
    for row in rows:
        row.sort(key=lambda w: w.x_left)
        lines.append(" ".join(w.text for w in row))

    return "\n".join(lines)


def run_ocr(image_bytes: bytes) -> tuple[str, list["OcrWord"]]:
    """
    Runs Tesseract ONCE, combined across all supported languages (see
    _TESSERACT_LANGS) via pytesseract -- a thin wrapper around the
    system `tesseract` CLI binary. Returns BOTH a reassembled text
    string (for display/debugging/detect_language -- see
    _reconstruct_reading_order's doc comment) AND the raw per-word list
    with positions (for parse_label's geometric matching) -- computed
    from the SAME single image_to_data call, not two separate OCR runs.
    """
    image = Image.open(BytesIO(image_bytes)).convert("RGB")

    # Downscale before OCR -- see _MAX_OCR_DIMENSION's doc comment.
    # thumbnail() preserves aspect ratio and is a no-op if the image is
    # already smaller than this.
    image.thumbnail((_MAX_OCR_DIMENSION, _MAX_OCR_DIMENSION))

    data = pytesseract.image_to_data(image, lang=_TESSERACT_LANGS, output_type=pytesseract.Output.DICT)

    # Logged BEFORE any filtering -- confirmed against real output that
    # the exact same photo produced usable words on some attempts and
    # zero words on another with no code change upstream of this point
    # between those attempts (turned out to be transient -- but this
    # stays, since an unexplained empty result is worth being able to
    # diagnose immediately rather than guessing again if it recurs).
    non_blank_count = sum(1 for t in data.get("text", []) if t.strip())
    logger.info(
        "Tesseract image_to_data: image_size=%s total_entries=%d non_blank_words=%d",
        image.size,
        len(data.get("text", [])),
        non_blank_count,
    )

    words = _words_from_tesseract_data(data)
    text = _reconstruct_reading_order(words)
    return text, words


def detect_language(text: str) -> Optional[str]:
    """Picks the language whose keyword dictionary has the most hits in
    the OCR'd text -- more robust than general-purpose language
    detection here, since it directly measures "which dictionary would
    actually let us extract fields" rather than guessing at prose language,
    which is unreliable against text that's mostly numbers and labels.
    Uses _score_text_for_language() above for the actual keyword
    counting. Position-insensitive (just counts whether keywords appear
    ANYWHERE), so this works fine even against imperfectly-reconstructed
    text -- unlike parse_label below, which needs actual positions and
    does NOT use this reconstructed text for that reason."""
    text_lower = text.lower()
    best_lang = None
    best_score = 0

    for lang, config in LANGUAGE_CONFIGS.items():
        score = _score_text_for_language(text_lower, config)
        if score > best_score:
            best_score = score
            best_lang = lang

    return best_lang if best_score >= 3 else None  # require a minimum confidence


def _phrase_ratio(words: list["OcrWord"], keyword: str) -> float:
    """Fuzzy match ratio between a sequence of words (joined) and a
    (possibly multi-word) keyword phrase."""
    candidate = " ".join(w.text.lower() for w in words)
    return difflib.SequenceMatcher(None, candidate, keyword.lower()).ratio()


def _find_keyword_anchor(
    words: list["OcrWord"], keyword: str, exclude_prefixes: Optional[list[str]] = None
) -> Optional["OcrWord"]:
    """
    Finds the best fuzzy match for `keyword` (which may be multiple
    words, e.g. "mættede fedtsyrer") among `words`, and returns the
    LAST word of that match -- used as the anchor for "look to the
    right of this position" when searching for the associated number.

    This is the core of the geometric rewrite: instead of reassembling
    everything into lines of text first and hoping the reassembly put
    each keyword on the same line as its own value (which kept failing
    in different ways on both OCR engines this app has tried -- see git
    history), this works directly off each word's actual position,
    so ONE bad row-boundary elsewhere on the label can't misattribute
    THIS keyword's value.

    exclude_prefixes skips a match if the SAME row (words within a
    height-based vertical tolerance) has one of those phrases
    immediately to its LEFT -- used to distinguish a parent total row
    (e.g. "FEDT") from a "HERAF ... FEDTSYRER" sub-row that also
    happens to contain the parent keyword as a substring of a longer
    word (e.g. "fedt" is literally a substring of "fedtsyrer").
    """
    exclude_prefixes = exclude_prefixes or []
    kw_word_count = len(keyword.split())

    best_match: Optional[OcrWord] = None
    best_score = 0.0

    for i in range(len(words) - kw_word_count + 1):
        window = words[i:i + kw_word_count]

        # Only consider windows that plausibly form one phrase -- words
        # roughly on the same row, in left-to-right order, not wildly
        # spaced apart (guards against accidentally matching words from
        # unrelated positions that happen to be adjacent in the list).
        row_height = sum(w.height for w in window) / len(window)
        row_y = sum(w.y_center for w in window) / len(window)
        if any(abs(w.y_center - row_y) > row_height * 0.7 for w in window):
            continue
        if kw_word_count > 1:
            gaps_ok = all(
                (window[j + 1].x_left - window[j].x_right) <= row_height * 3
                for j in range(len(window) - 1)
            )
            if not gaps_ok:
                continue

        score = _phrase_ratio(window, keyword)
        if score < 0.75 or score <= best_score:
            continue

        # Exclude-prefix check -- any word on the same row, entirely to
        # the left of this match, that fuzzy-matches an exclude prefix
        # disqualifies this occurrence.
        left_words = [w for w in words if abs(w.y_center - row_y) <= row_height * 0.7 and w.x_right <= window[0].x_left]
        left_text = " ".join(w.text.lower() for w in left_words)
        if any(_fuzzy_contains(left_text, p) for p in exclude_prefixes):
            continue

        best_score = score
        best_match = window[-1]

    return best_match


def _find_number_to_right(
    words: list["OcrWord"], anchor: "OcrWord", require_word: Optional[str] = None
) -> Optional[Decimal]:
    """
    Finds a number on the same row as `anchor`, to its right. If
    `require_word` is given (used for energy specifically -- see
    _extract_kcal_geometric), only considers a number immediately to
    the LEFT of a word fuzzy-matching `require_word` (e.g. "kcal"),
    rather than just the nearest number to the right of the keyword --
    an energy row shows BOTH a kJ and a kcal figure, and we need
    specifically the one paired with "kcal".
    """
    same_row = [
        w for w in words
        if w is not anchor and abs(w.y_center - anchor.y_center) <= max(anchor.height, 1) * 0.7
    ]

    if require_word:
        unit_word = next(
            (w for w in same_row if _fuzzy_contains(w.text.lower(), require_word.lower())),
            None,
        )
        if unit_word is None:
            return None
        # Nearest number-containing word strictly to the left of the
        # unit word (kcal), among words also to the right of the
        # keyword anchor itself.
        candidates = [
            w for w in same_row
            if w.x_right <= unit_word.x_left and w.x_left >= anchor.x_right
        ]
        candidates.sort(key=lambda w: unit_word.x_left - w.x_right)
    else:
        candidates = [w for w in same_row if w.x_left >= anchor.x_right]
        candidates.sort(key=lambda w: w.x_left - anchor.x_right)

    for w in candidates:
        # Restricted to at most ONE decimal digit -- EU nutrition labels
        # universally report to 1 decimal place. This was present in the
        # original text-based extraction and got dropped when this was
        # rewritten for geometric matching -- confirmed against real
        # output that dropping it reintroduced exactly the bug it
        # existed to prevent: a blurred "g" unit letter misread by
        # Tesseract as a digit "9" with no separating space (e.g.
        # "12.2 g" -> "12.29") was getting silently absorbed into the
        # value. Capping at one decimal digit means that corrupted
        # extra digit is correctly left uncaptured.
        match = re.search(r"(\d+(?:[.,]\d)?)", w.text)
        if match:
            number_str = match.group(1).replace(",", ".")
            try:
                return Decimal(number_str)
            except Exception:
                continue

    return None


def _extract_field(
    words: list["OcrWord"], keyword: str, exclude_prefixes: Optional[list[str]] = None
) -> Optional[Decimal]:
    """Standard "find this keyword, take the nearest number to its
    right" extraction -- used for every macro except energy (see
    _extract_kcal_geometric, which needs the number paired with "kcal"
    specifically, not just the nearest one)."""
    anchor = _find_keyword_anchor(words, keyword, exclude_prefixes)
    if anchor is None:
        return None
    return _find_number_to_right(words, anchor)


def _extract_kcal_geometric(words: list["OcrWord"], energy_keyword: str) -> Optional[Decimal]:
    """Energy rows typically show both kJ and kcal (e.g. "1046 kJ / 250
    kcal") -- we want specifically the number paired with "kcal", not
    the kJ value, which _find_number_to_right's require_word handles."""
    anchor = _find_keyword_anchor(words, energy_keyword)
    if anchor is None:
        return None
    return _find_number_to_right(words, anchor, require_word="kcal")


def parse_label(words: list["OcrWord"], text: str, lang: str) -> OcrExtractionResult:
    """
    Extracts macros by finding each keyword's own position and looking
    for a number geometrically near it (see _find_keyword_anchor/
    _find_number_to_right), NOT by reassembling OCR output into lines
    of text and pattern-matching within a line. That line-based
    approach was tried on BOTH OCR engines this app has used and failed
    differently each time -- EasyOCR's raw detection order put every
    number in one cluster and every label in another; Tesseract's own
    line grouping and this app's own several attempts at reconstructing
    it all shifted rows in various ways, one bug replaced by a
    different one each time a threshold got adjusted. The common thread
    was that ANY single global mistake in reassembling the WHOLE label
    into text broke EVERY field that came after it. Matching each
    keyword directly against nearby word positions means one bad
    match/row only affects that ONE field, not everything downstream of
    it -- a smaller, more contained failure mode.

    `text` (the reassembled string) is still used for the per_100g
    marker check below, since that's just "does this substring appear
    anywhere" and isn't sensitive to row alignment the way a specific
    keyword-to-value pairing is.
    """
    config = LANGUAGE_CONFIGS.get(lang)
    if not config:
        return OcrExtractionResult(raw_text=text, detected_language=lang, per_100g_confirmed=False)

    text_lower = text.lower()
    per_100g_confirmed = any(marker in text_lower for marker in config.per_100g_markers)

    macros: dict = {}

    kcal = _extract_kcal_geometric(words, config.energy_keyword)
    if kcal is not None:
        macros["kcal_100g"] = kcal

    fat = _extract_field(words, config.fat_keyword, config.fat_exclude_prefixes)
    if fat is not None:
        macros["fat_100g"] = fat

    sat_fat = _extract_field(words, config.saturated_fat_keyword)
    if sat_fat is not None:
        macros["saturated_fat_100g"] = sat_fat

    carbs = _extract_field(words, config.carbs_keyword, config.carbs_exclude_prefixes)
    if carbs is not None:
        macros["carbs_100g"] = carbs

    sugar = _extract_field(words, config.sugar_keyword)
    if sugar is not None:
        macros["sugar_100g"] = sugar

    fiber = _extract_field(words, config.fiber_keyword)
    if fiber is not None:
        macros["fiber_100g"] = fiber

    protein = _extract_field(words, config.protein_keyword)
    if protein is not None:
        macros["protein_100g"] = protein

    # Sodium: prefer a direct sodium value if the label has one, else
    # convert from salt (EU convention) -- see app/nutrition.py for why
    # this conversion must happen here, before anything reaches the DB.
    if config.sodium_keyword:
        sodium = _extract_field(words, config.sodium_keyword)
        if sodium is not None:
            macros["sodium_mg_100g"] = sodium * Decimal("1000")  # assume g reported, convert to mg

    if "sodium_mg_100g" not in macros and config.salt_keyword:
        salt = _extract_field(words, config.salt_keyword)
        if salt is not None:
            macros["sodium_mg_100g"] = salt_g_to_sodium_mg(salt)

    return OcrExtractionResult(
        raw_text=text,
        detected_language=lang,
        per_100g_confirmed=per_100g_confirmed,
        macros=macros,
    )


def extract_label_from_image(image_bytes: bytes) -> OcrExtractionResult:
    """Full pipeline: OCR -> detect language -> parse fields."""
    text, words = run_ocr(image_bytes)
    lang = detect_language(text)

    if lang is None:
        return OcrExtractionResult(raw_text=text, detected_language=None, per_100g_confirmed=False)

    return parse_label(words, text, lang)


# Lines that are mostly digits/punctuation are almost always a barcode
# number, a net-weight declaration ("250 g", "12x330ml"), a best-before
# date, or similar -- never a product name or brand. Filtering these out
# before guessing name/brand meaningfully improves the heuristic below.
# Threshold: if fewer than 40% of a line's non-space characters are
# letters, treat it as "mostly not text" and skip it.
_MIN_LETTER_FRACTION = 0.4


def _looks_like_text(line: str) -> bool:
    stripped = line.strip()
    if not stripped:
        return False
    letters = sum(1 for ch in stripped if ch.isalpha())
    non_space = sum(1 for ch in stripped if not ch.isspace())
    if non_space == 0:
        return False
    return (letters / non_space) >= _MIN_LETTER_FRACTION


def guess_name_and_brand(text: str) -> tuple[Optional[str], Optional[str]]:
    """
    Best-effort, NOT confident structured extraction (see
    ProductPhotoScanResult's docstring in app/schemas.py for why this is
    fundamentally different from the nutrition-label parsing above --
    there's no consistent field-label vocabulary on a product package
    front to anchor on).

    Heuristic: take the "text-like" lines (filtering out barcode/weight/
    date-looking lines via _looks_like_text), guess the LONGEST such line
    is the product name -- on real packaging the product name is
    typically the most prominent (often largest-font) text, and while
    OCR doesn't give us font-size info to check that directly, line
    length is a rough proxy that's easy to compute and better than
    guessing randomly. The brand guess is simply the next-longest
    distinct line, on the (weak, often wrong) assumption that the brand
    name is the second most prominent text on the front of the pack.

    Both are meant to pre-fill editable text fields the user reviews,
    never to be trusted as-is -- callers must treat these as drafts.
    """
    candidates = [line.strip() for line in text.split("\n") if _looks_like_text(line)]
    if not candidates:
        return None, None

    # Sort by length, longest first, keeping only the first occurrence of
    # each distinct line (dedupes cases where OCR repeats a line, e.g.
    # from a logo appearing twice in-frame).
    seen: set[str] = set()
    deduped: list[str] = []
    for line in sorted(candidates, key=len, reverse=True):
        if line.lower() not in seen:
            seen.add(line.lower())
            deduped.append(line)

    guessed_name = deduped[0] if len(deduped) >= 1 else None
    guessed_brand = deduped[1] if len(deduped) >= 2 else None
    return guessed_name, guessed_brand