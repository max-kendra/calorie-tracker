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

IMPORTANT -- tested against real Tesseract output (not just designed on
paper): a synthetic but realistically-rendered Danish label, run through
the actual installed `dan` Tesseract model, exposed a real OCR quirk --
"Salt 1,2 g" was recognized as "Salt1,2g" (Tesseract dropped the space).
The parsing regexes below are written to tolerate missing whitespace
between a keyword and its number for exactly this reason.

HONESTY ABOUT LANGUAGE COVERAGE: Danish, German, and English keyword
dictionaries below have been verified against real Tesseract OCR output
on synthetic label images (see tests). Swedish, Finnish, Norwegian,
Spanish, Slovak, and Czech dictionaries use standard EU nutrition-label
vocabulary (consistent under EU FIC labeling regulation) but have NOT
been run through real OCR here -- they should be treated as a reasonable
starting point, not verified, until tested against real labels in those
languages.
"""

import re
from dataclasses import dataclass, field
from decimal import Decimal
from io import BytesIO
from typing import Optional

import pytesseract
from PIL import Image

from app.nutrition import salt_g_to_sodium_mg

ALL_LANGS = "dan+deu+eng+swe+fin+nor+spa+slk+ces"


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


def run_ocr(image_bytes: bytes, languages: str = ALL_LANGS) -> str:
    image = Image.open(BytesIO(image_bytes))
    return pytesseract.image_to_string(image, lang=languages)


def detect_language(text: str) -> Optional[str]:
    """Picks the language whose keyword dictionary has the most hits in
    the OCR'd text -- more robust than general-purpose language
    detection here, since it directly measures "which dictionary would
    actually let us extract fields" rather than guessing at prose language,
    which is unreliable against text that's mostly numbers and labels."""
    text_lower = text.lower()
    best_lang = None
    best_score = 0

    for lang, config in LANGUAGE_CONFIGS.items():
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

        score = sum(1 for kw in keywords if re.search(kw, text_lower))
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