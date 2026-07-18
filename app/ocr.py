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

The parsing/keyword logic below (`_extract_number_near_keyword`,
`_extract_kcal`, `LANGUAGE_CONFIGS`, etc.) is engine-agnostic -- it just
operates on whatever text string the OCR step returns, one line per
detected line of text -- so none of that needed to change switching back
to this engine. Tesseract's own layout analysis generally preserves
proper reading order for structured documents on its own, unlike
EasyOCR's raw detection order (see the reading-order bug that used to
live here, on the EasyOCR branch) -- if garbled/misordered output shows
up again on real labels, pytesseract.image_to_data() exposes the same
per-word bounding-box info that fix relied on, and the same row-grouping
technique could be reapplied here.

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


def run_ocr(image_bytes: bytes) -> str:
    """
    Runs Tesseract ONCE, combined across all supported languages (see
    _TESSERACT_LANGS) via pytesseract -- a thin wrapper around the
    system `tesseract` CLI binary. Which language the text is actually
    in gets figured out afterward by detect_language() (see
    extract_label_from_image()), via the same keyword-scoring approach
    used on every other branch -- unchanged, since that logic is
    engine-agnostic.
    """
    image = Image.open(BytesIO(image_bytes)).convert("RGB")

    # Downscale before OCR -- see _MAX_OCR_DIMENSION's doc comment.
    # thumbnail() preserves aspect ratio and is a no-op if the image is
    # already smaller than this.
    image.thumbnail((_MAX_OCR_DIMENSION, _MAX_OCR_DIMENSION))

    return pytesseract.image_to_string(image, lang=_TESSERACT_LANGS)


def detect_language(text: str) -> Optional[str]:
    """Picks the language whose keyword dictionary has the most hits in
    the OCR'd text -- more robust than general-purpose language
    detection here, since it directly measures "which dictionary would
    actually let us extract fields" rather than guessing at prose language,
    which is unreliable against text that's mostly numbers and labels.
    Uses _score_text_for_language() above for the actual keyword
    counting."""
    text_lower = text.lower()
    best_lang = None
    best_score = 0

    for lang, config in LANGUAGE_CONFIGS.items():
        score = _score_text_for_language(text_lower, config)
        if score > best_score:
            best_score = score
            best_lang = lang

    return best_lang if best_score >= 3 else None  # require a minimum confidence


def _extract_number_near_keyword(
    text: str, keyword: str, exclude_prefixes: Optional[list[str]] = None
) -> Optional[Decimal]:
    """
    Finds a line containing `keyword` (fuzzy match -- see
    _fuzzy_contains, tolerates the kind of OCR misreads seen in
    practice, e.g. "fedt" read as "feot"), not starting with any of
    `exclude_prefixes` (used to skip "of which X" sub-lines when looking
    for the parent total), and extracts the first number on that line.
    Tolerates missing whitespace between the keyword and the number
    (e.g. "Salt1,2g") and European comma decimal separators.
    """
    exclude_prefixes = exclude_prefixes or []

    for line in text.split("\n"):
        line_lower = line.lower().strip()
        if not _fuzzy_contains(line_lower, keyword):
            continue
        if any(line_lower.startswith(p) for p in exclude_prefixes):
            continue

        # Restricted to at most ONE decimal digit -- EU nutrition labels
        # universally report to 1 decimal place. Found via real degraded-
        # image testing that a blurred "g" unit letter can be misread by
        # Tesseract as a digit "9" with no separating space (e.g. "25,0 g"
        # -> "25,09"), which a greedy \d* would incorrectly absorb into
        # the value. Capping at one decimal digit means that corrupted
        # extra digit is correctly left uncaptured.
        match = re.search(r"(\d+(?:[.,]\d)?)", line)
        if match:
            number_str = match.group(1).replace(",", ".")
            try:
                return Decimal(number_str)
            except Exception:
                continue

    return None


def _extract_kcal(text: str, energy_keyword: str) -> Optional[Decimal]:
    """Energy lines typically show both kJ and kcal (e.g. "1046 kJ/250
    kcal") -- we want specifically the number immediately before "kcal",
    not the kJ value. energy_keyword itself is fuzzy-matched (see
    _fuzzy_contains) for the same OCR-misread reason as
    _extract_number_near_keyword -- "kcal" itself is left as an exact
    match in the regex below since it's rarely misread and being lenient
    there risks false positives against unrelated numbers."""
    for line in text.split("\n"):
        if not _fuzzy_contains(line.lower(), energy_keyword):
            continue
        match = re.search(r"(\d+(?:[.,]\d)?)\s*kcal", line, re.IGNORECASE)
        if match:
            return Decimal(match.group(1).replace(",", "."))
    return None


def parse_label(text: str, lang: str) -> OcrExtractionResult:
    config = LANGUAGE_CONFIGS.get(lang)
    if not config:
        return OcrExtractionResult(raw_text=text, detected_language=lang, per_100g_confirmed=False)

    text_lower = text.lower()
    per_100g_confirmed = any(marker in text_lower for marker in config.per_100g_markers)

    macros: dict = {}

    kcal = _extract_kcal(text, config.energy_keyword)
    if kcal is not None:
        macros["kcal_100g"] = kcal

    fat = _extract_number_near_keyword(text, config.fat_keyword, config.fat_exclude_prefixes)
    if fat is not None:
        macros["fat_100g"] = fat

    sat_fat = _extract_number_near_keyword(text, config.saturated_fat_keyword)
    if sat_fat is not None:
        macros["saturated_fat_100g"] = sat_fat

    carbs = _extract_number_near_keyword(text, config.carbs_keyword, config.carbs_exclude_prefixes)
    if carbs is not None:
        macros["carbs_100g"] = carbs

    sugar = _extract_number_near_keyword(text, config.sugar_keyword)
    if sugar is not None:
        macros["sugar_100g"] = sugar

    fiber = _extract_number_near_keyword(text, config.fiber_keyword)
    if fiber is not None:
        macros["fiber_100g"] = fiber

    protein = _extract_number_near_keyword(text, config.protein_keyword)
    if protein is not None:
        macros["protein_100g"] = protein

    # Sodium: prefer a direct sodium value if the label has one, else
    # convert from salt (EU convention) -- see app/nutrition.py for why
    # this conversion must happen here, before anything reaches the DB.
    if config.sodium_keyword:
        sodium = _extract_number_near_keyword(text, config.sodium_keyword)
        if sodium is not None:
            macros["sodium_mg_100g"] = sodium * Decimal("1000")  # assume g reported, convert to mg

    if "sodium_mg_100g" not in macros and config.salt_keyword:
        salt = _extract_number_near_keyword(text, config.salt_keyword)
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
    text = run_ocr(image_bytes)
    lang = detect_language(text)

    if lang is None:
        return OcrExtractionResult(raw_text=text, detected_language=None, per_100g_confirmed=False)

    return parse_label(text, lang)


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