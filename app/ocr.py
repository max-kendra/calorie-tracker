"""
OCR extraction for nutrition labels.

Flow: run EasyOCR across all supported languages combined -> pick the
best-matching language by counting keyword hits per language's dictionary
-> parse per-100g macro values from the recognized text using that
language's keyword patterns -> convert salt->sodium if the label uses
salt (EU convention) rather than sodium directly.

This NEVER writes to our DB -- same pattern as USDA and barcode scanning:
the result is a best-effort draft for the client to show in the Add Item
review form, which the user corrects before anything is saved.

ENGINE NOTE: this used to run on Tesseract (pytesseract). We switched to
EasyOCR because it was noticeably more accurate on real, imperfectly-lit
photos of labels -- Tesseract needed fairly clean, well-cropped input to
do well, which didn't match how people actually photograph labels in the
app. Tradeoff: EasyOCR runs on PyTorch, so it's heavier (bigger image,
slower per-request CPU inference, and it downloads model weights on
first run) than Tesseract was, which matters if this is deployed on a
Raspberry Pi -- worth benchmarking real request latency post-deploy.

The parsing/keyword logic below (`_extract_number_near_keyword`,
`_extract_kcal`, `LANGUAGE_CONFIGS`, etc.) is engine-agnostic -- it just
operates on whatever text string the OCR step returns, one line per
detected text region -- so none of that needed to change. The comments
in there about specific Tesseract quirks (dropped whitespace, misread
characters) are historical context from when this was tuned against
Tesseract output; they're left in place since tolerating that kind of
noise is harmless even if EasyOCR doesn't happen to exhibit the exact
same quirks, but they haven't been re-verified against real EasyOCR
output. Treat that the same way as the "HONESTY ABOUT LANGUAGE COVERAGE"
note below -- re-verify against real EasyOCR output before trusting it
fully in production.

HONESTY ABOUT LANGUAGE COVERAGE: Danish, German, and English keyword
dictionaries below were verified against real Tesseract OCR output on
synthetic label images (see tests) -- not yet re-verified against
EasyOCR output. Swedish, Finnish, Norwegian, Spanish, Slovak, and Czech
dictionaries use standard EU nutrition-label vocabulary (consistent
under EU FIC labeling regulation) but have NOT been run through real OCR
of either engine here -- they should be treated as a reasonable starting
point, not verified, until tested against real labels in those
languages.
"""

import re
import logging
import threading
from dataclasses import dataclass, field
from decimal import Decimal
from io import BytesIO
from typing import Optional

import easyocr
import numpy as np
from PIL import Image

from app.nutrition import salt_g_to_sodium_mg

logger = logging.getLogger(__name__)

# EasyOCR language codes for the 9 languages we support -- these are NOT
# the same codes Tesseract used (e.g. "dan" -> "da", "deu" -> "de"). The
# LANGUAGE_CONFIGS dict below still keys off the Tesseract-style codes
# ("dan", "deu", ...) since that's what detect_language()/parse_label()
# use internally and what the rest of the app (tests, API responses)
# expects -- this map is only used to talk to EasyOCR itself.
_EASYOCR_LANG_CODES = {
    "dan": "da",
    "deu": "de",
    "eng": "en",
    "swe": "sv",
    "fin": "fi",
    "nor": "no",
    "spa": "es",
    "slk": "sk",
    "ces": "cs",
}

# EasyOCR's Reader is expensive to construct (loads model weights from
# disk/downloads them on first run) -- build it once lazily and reuse it
# across requests rather than per-call. Guarded with a lock since FastAPI
# may serve requests concurrently and Reader construction isn't meant to
# be called from multiple threads at once.
#
# CORRECTED (reverting a wrong "fix" from earlier): this was briefly
# changed to build a SEPARATE Reader per language (each paired with
# English), on the assumption that EasyOCR can't combine unrelated
# languages into one Reader. That assumption was wrong for this case --
# EasyOCR groups languages by SCRIPT, and every language on our list
# (English, Danish, Norwegian, Swedish, Finnish, Slovak, Czech, Polish,
# French, Spanish) shares the same Latin-script model; combining them
# loads ONE model with the union of each language's diacritics, not 9
# incompatible ones. Running 8-9 SEPARATE Readers instead was
# significantly heavier (multiple full model loads + multiple inference
# passes per request) than the original single combined Reader ever
# was, and that's what was actually behind the new OCR/product-photo-
# scan timeouts and 500s/502s -- not a language-compatibility bug at
# all. Reverted to a single combined Reader, which is both simpler and
# correct.
#
# If Reader construction ever does fail for a genuinely bad reason, this
# now logs the real exception instead of silently swallowing it (that
# silent-swallow behavior in the caller -- see scan_label's try/except
# in app/routers/items.py -- is what let the previous, wrong diagnosis
# go unnoticed for as long as it did).
_reader: Optional[easyocr.Reader] = None
_reader_lock = threading.Lock()


def _get_reader() -> easyocr.Reader:
    global _reader
    if _reader is None:
        with _reader_lock:
            if _reader is None:  # re-check inside the lock
                try:
                    _reader = easyocr.Reader(list(_EASYOCR_LANG_CODES.values()), gpu=False)
                except Exception:
                    logger.exception(
                        "EasyOCR Reader construction failed for languages: %s",
                        list(_EASYOCR_LANG_CODES.values()),
                    )
                    raise
    return _reader


@dataclass
class LabelLanguageConfig:
    per_100g_markers: list[str]
    energy_keyword: str  # matched directly before "kcal"
    fat_keyword: str
    fat_exclude_prefixes: list[str]  # lines starting with these are "of which" sub-lines
    saturated_fat_keyword: str
    carbs_keyword: str
    carbs_exclude_prefixes: list[str]
    sugar_keyword: str
    fiber_keyword: str
    protein_keyword: str
    salt_keyword: Optional[str] = None  # EU labels: salt in grams
    sodium_keyword: Optional[str] = None  # some labels show sodium directly


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
}


@dataclass
class OcrExtractionResult:
    raw_text: str
    detected_language: Optional[str]
    per_100g_confirmed: bool  # whether a "per 100g" marker was actually found
    macros: dict = field(default_factory=dict)


def _score_text_for_language(text_lower: str, config: "LabelLanguageConfig") -> int:
    """How many of this language's macro keywords show up in the text --
    used by detect_language() below to pick the best-matching language
    for a single shared OCR pass."""
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
    return sum(1 for kw in keywords if re.search(kw, text_lower))


def run_ocr(image_bytes: bytes) -> str:
    """
    Runs EasyOCR ONCE, combined across all supported languages (see
    _get_reader()'s doc comment for why one combined Reader is correct
    here, not one per language), and returns the recognized text as one
    line per detected text region (newline-joined) -- matching the
    line-oriented shape the parsing functions below expect, same shape
    pytesseract.image_to_string used to produce. Which language the text
    is actually in gets figured out afterward by detect_language() (see
    extract_label_from_image()), via the same keyword-scoring approach
    that used to also run once per language here -- now it only needs to
    run once, since there's only one OCR pass to score.
    """
    image = Image.open(BytesIO(image_bytes)).convert("RGB")
    reader = _get_reader()
    # detail=0 -> just the recognized strings (no bounding boxes/
    # confidence); paragraph=False -> keep individual detected lines
    # separate rather than merging into paragraphs, since the per-line
    # keyword matching below relies on each nutrition-label row being
    # its own line.
    lines = reader.readtext(np.array(image), detail=0, paragraph=False)
    return "\n".join(lines)


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
    Finds a line containing `keyword`, not starting with any of
    `exclude_prefixes` (used to skip "of which X" sub-lines when looking
    for the parent total), and extracts the first number on that line.
    Tolerates missing whitespace between the keyword and the number
    (e.g. "Salt1,2g") and European comma decimal separators.
    """
    exclude_prefixes = exclude_prefixes or []

    for line in text.split("\n"):
        line_lower = line.lower().strip()
        if not re.search(keyword, line_lower):
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
    not the kJ value."""
    for line in text.split("\n"):
        if not re.search(energy_keyword, line.lower()):
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
    EasyOCR doesn't give us font-size info to check that directly, line
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