"""
USDA FoodData Central API client - used for the "raw ingredient
reference" search/import flow (see design doc). Kept fully separate from
our own `items` table: this module only fetches and normalizes external
data. Nothing here writes to our DB directly - the client (Android/web)
gets normalized macro data back to pre-fill the Add Item review form,
and the actual item is created via the normal POST /items call once the
user reviews/confirms, same pattern as OCR and barcode scanning (see
app/barcode.py for why "always let the user confirm" matters).

NOTE: FDC's search endpoint and detail endpoint return nutrient data in
slightly different shapes:
  - detail endpoint:  {"nutrient": {"name": "Protein", "unitName": "G"}, "amount": 1.1}
  - search endpoint:  {"nutrientName": "Protein", "unitName": "G", "value": 1.1}
_normalize_nutrient_entry() below handles both.

IMPORTANT nutrient-matching gotcha, found via testing against a
realistic sample payload (not caught by initial naive implementation):
FDC often lists MULTIPLE sub-types for the same macro - e.g. "Fiber,
soluble" / "Fiber, insoluble" / "Fiber, total dietary" all present at
once, or "Sugars, added" alongside "Sugars, total including NLEA". A
naive substring match on "fiber" or "sugars" can silently grab the wrong
sub-type (e.g. soluble fiber instead of total, or added sugar = 0
instead of total sugar). Fixed by matching against explicit priority
lists per macro, always preferring "total" phrasing, checked across ALL
nutrient entries before falling back to a looser match.

IMPORTANT: this module has NOT been tested against a live API response
in this environment - USDA's domain isn't in this sandbox's network
allowlist, and the public DEMO_KEY is rate-limited/blocked through the
available fetch tooling here. The request/response handling follows
USDA's documented API format exactly, and the nutrient-matching logic
(including the sub-type gotcha above) has been verified against
realistic sample payloads, but a real end-to-end test against the live
API needs to happen wherever this actually runs with real network access
(the Pi). Get a free API key at https://fdc.nal.usda.gov/api-key-signup
- the public DEMO_KEY works for initial testing but is heavily
rate-limited (30/hour, 50/day).
"""

from dataclasses import dataclass, field
from decimal import Decimal
from typing import Optional

import httpx

FDC_BASE_URL = "https://api.nal.usda.gov/fdc/v1"


def _normalize_nutrient_entry(entry: dict) -> tuple[str, str, float] | None:
    """Returns (name, unit, amount) regardless of which of FDC's two
    nutrient shapes this entry uses, or None if it doesn't match either."""
    if "nutrient" in entry and "amount" in entry:
        nutrient = entry.get("nutrient") or {}
        name = nutrient.get("name")
        unit = nutrient.get("unitName")
        amount = entry.get("amount")
    elif "nutrientName" in entry and "value" in entry:
        name = entry.get("nutrientName")
        unit = entry.get("unitName")
        amount = entry.get("value")
    else:
        return None

    if name is None or amount is None:
        return None
    return name, (unit or ""), amount


# Each macro maps to a list of (required_phrases, excluded_phrases,
# required_unit) tuples, tried IN ORDER across ALL nutrient entries -
# the first pattern that matches anything wins, so more specific/"total"
# phrasings are always tried before looser ones. required_unit=None means
# any unit is accepted.
_MATCH_PRIORITIES: dict[str, list[tuple[list[str], list[str], Optional[str]]]] = {
    "kcal_100g": [
        (["energy"], [], "kcal"),
    ],
    "protein_100g": [
        (["protein"], [], None),
    ],
    "carbs_100g": [
        (["carbohydrate, by difference"], [], None),
        (["carbohydrate"], [], None),
    ],
    "fat_100g": [
        (["total lipid"], [], None),
        (["fat"], ["saturated", "unsaturated", "trans", "monounsaturated", "polyunsaturated"], None),
    ],
    "fiber_100g": [
        (["fiber", "total"], [], None),
        (["fiber"], ["soluble", "insoluble"], None),
    ],
    "sugar_100g": [
        (["sugars", "total"], ["added"], None),
        (["sugars"], ["added"], None),
    ],
    "saturated_fat_100g": [
        (["fatty acid", "saturated"], [], None),
        (["saturated fatty acid"], [], None),
    ],
    "sodium_mg_100g": [
        (["sodium"], [], None),
    ],
}


def extract_macros(food_nutrients: list[dict]) -> dict:
    """
    Best-effort extraction of our tracked macros from an FDC food's
    nutrient list. Returns only the fields it found a confident match
    for - missing fields are simply absent from the returned dict
    (caller/client should treat those as unknown, not zero).

    Uses priority-ordered matching per macro (see _MATCH_PRIORITIES) to
    avoid grabbing the wrong sub-type when a food lists multiple related
    nutrients (e.g. soluble/insoluble/total fiber all present).
    """
    normalized_entries = []
    for entry in food_nutrients:
        n = _normalize_nutrient_entry(entry)
        if n:
            normalized_entries.append(n)

    macros: dict = {}

    for field_name, priority_list in _MATCH_PRIORITIES.items():
        for required_phrases, excluded_phrases, required_unit in priority_list:
            found = False
            for name, unit, amount in normalized_entries:
                name_lower = name.lower()

                if not all(p in name_lower for p in required_phrases):
                    continue
                if any(p in name_lower for p in excluded_phrases):
                    continue
                if required_unit and unit.lower() != required_unit.lower():
                    continue

                macros[field_name] = Decimal(str(amount))
                found = True
                break

            if found:
                break  # don't try looser patterns once a match is found

    return macros


@dataclass
class UsdaFoodSummary:
    """Lightweight result from a search - enough to show in a result list."""

    fdc_id: int
    description: str
    data_type: str
    brand_owner: Optional[str] = None
    macros: dict = field(default_factory=dict)


@dataclass
class UsdaFoodDetail:
    """Full detail for one food - used when the user picks a search result."""

    fdc_id: int
    description: str
    data_type: str
    macros: dict = field(default_factory=dict)


class UsdaClient:
    def __init__(self, api_key: str, timeout: float = 10.0):
        self.api_key = api_key
        self.timeout = timeout

    def search(
        self, query: str, data_types: Optional[list[str]] = None, page_size: int = 10
    ) -> list[UsdaFoodSummary]:
        """
        Searches FDC. Callers building the raw-ingredient-reference flow
        should typically pass data_types=["Foundation", "SR Legacy"] -
        these are the lab-analyzed, stable datasets for raw/whole foods
        (see design doc), as opposed to "Branded" which is user-submitted
        label data better suited to barcode lookups than name search.
        """
        params = {"api_key": self.api_key, "query": query, "pageSize": page_size}
        if data_types:
            params["dataType"] = ",".join(data_types)

        with httpx.Client(timeout=self.timeout) as client:
            response = client.get(f"{FDC_BASE_URL}/foods/search", params=params)
            response.raise_for_status()
            data = response.json()

        results = []
        for food in data.get("foods", []):
            macros = extract_macros(food.get("foodNutrients", []))
            results.append(
                UsdaFoodSummary(
                    fdc_id=food["fdcId"],
                    description=food.get("description", ""),
                    data_type=food.get("dataType", ""),
                    brand_owner=food.get("brandOwner"),
                    macros=macros,
                )
            )
        return results

    def get_food(self, fdc_id: int) -> UsdaFoodDetail:
        """Fetches full detail for one food by FDC ID."""
        params = {"api_key": self.api_key}

        with httpx.Client(timeout=self.timeout) as client:
            response = client.get(f"{FDC_BASE_URL}/food/{fdc_id}", params=params)
            response.raise_for_status()
            food = response.json()

        macros = extract_macros(food.get("foodNutrients", []))
        return UsdaFoodDetail(
            fdc_id=food["fdcId"],
            description=food.get("description", ""),
            data_type=food.get("dataType", ""),
            macros=macros,
        )