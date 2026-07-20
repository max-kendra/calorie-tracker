"""
Shared nutrition math - used by recipes.py (live recipe totals) and
logs.py/meal_plans.py (computing what a logged/planned quantity of an
item or recipe actually amounts to).

Kept in one place so the "how do we turn a quantity into macros" logic
isn't duplicated and can't drift between routers.

Rounding policy: all computation here stays in precise Decimal (RawTotals).
Rounding UP to whole numbers only happens at the API response boundary,
via `to_display()`/`to_display_extended()`, called right before building a
response - never before storing or summing. Rounding every component up
before aggregating would compound into meaningfully inflated
weekly/monthly totals.

Sugar/saturated fat/sodium: tracked in RawTotals like every other macro
(so the math is always available), but deliberately excluded from the
compact `NutritionTotals` display type used on items/recipes/logs/
meal_plans, to avoid crowding those UIs. They're only surfaced via
`ExtendedNutritionTotals`, used solely by the daily/weekly summary endpoint.

Note on sodium: canonical unit is mg per 100g (matches USDA FoodData
Central). EU nutrition labels show "salt" in grams instead - conversion
is salt(g) = sodium(g) x 2.5. That conversion happens at data-entry time
in the client UI, not here.
"""

from dataclasses import dataclass
from decimal import ROUND_CEILING, Decimal

from app.models import Item, Recipe, ServingSize
from app.schemas import ExtendedNutritionTotals, NutritionTotals

ZERO = Decimal("0")


@dataclass
class RawTotals:
    """Precise, internal-only representation. Never returned directly
    from an API endpoint - always pass through to_display()/
    to_display_extended() first."""

    kcal: Decimal
    protein_g: Decimal
    carbs_g: Decimal
    fat_g: Decimal
    fiber_g: Decimal
    sugar_g: Decimal
    # Sugar excluding raw USDA-import-origin ingredients (see design
    # discussion: "keep an eye on metrics" flagging bananas as a top
    # sugar source, when added-sugar dietary guidance is about added/
    # free sugars specifically, not sugar naturally occurring in whole
    # foods). A heuristic, not a true added-vs-total-sugar distinction -
    # packaged foods only ever give "carbs, of which sugars" with no
    # further breakdown, so item origin (raw USDA ingredient vs a
    # scanned/manual product) is the best available proxy.
    countable_sugar_g: Decimal
    saturated_fat_g: Decimal
    sodium_mg: Decimal

    def __add__(self, other: "RawTotals") -> "RawTotals":
        return RawTotals(
            kcal=self.kcal + other.kcal,
            protein_g=self.protein_g + other.protein_g,
            carbs_g=self.carbs_g + other.carbs_g,
            fat_g=self.fat_g + other.fat_g,
            fiber_g=self.fiber_g + other.fiber_g,
            sugar_g=self.sugar_g + other.sugar_g,
            countable_sugar_g=self.countable_sugar_g + other.countable_sugar_g,
            saturated_fat_g=self.saturated_fat_g + other.saturated_fat_g,
            sodium_mg=self.sodium_mg + other.sodium_mg,
        )

    def __mul__(self, factor: Decimal) -> "RawTotals":
        return RawTotals(
            kcal=self.kcal * factor,
            protein_g=self.protein_g * factor,
            carbs_g=self.carbs_g * factor,
            fat_g=self.fat_g * factor,
            fiber_g=self.fiber_g * factor,
            sugar_g=self.sugar_g * factor,
            countable_sugar_g=self.countable_sugar_g * factor,
            saturated_fat_g=self.saturated_fat_g * factor,
            sodium_mg=self.sodium_mg * factor,
        )

    def __truediv__(self, divisor: Decimal) -> "RawTotals":
        return self * (Decimal("1") / divisor)


ZERO_TOTALS = RawTotals(
    kcal=ZERO,
    protein_g=ZERO,
    carbs_g=ZERO,
    fat_g=ZERO,
    fiber_g=ZERO,
    sugar_g=ZERO,
    countable_sugar_g=ZERO,
    saturated_fat_g=ZERO,
    sodium_mg=ZERO,
)


def ceil_int(value: Decimal) -> int:
    """Round UP to the nearest whole number - display layer only."""
    return int(value.to_integral_value(rounding=ROUND_CEILING))


def to_display(totals: RawTotals) -> NutritionTotals:
    """Convert precise internal totals to the compact, API-facing shape
    used on items/recipes/logs/meal_plans. Deliberately drops
    sugar/saturated_fat/sodium - use to_display_extended() for summaries."""
    return NutritionTotals(
        kcal=ceil_int(totals.kcal),
        protein_g=ceil_int(totals.protein_g),
        carbs_g=ceil_int(totals.carbs_g),
        fat_g=ceil_int(totals.fat_g),
        fiber_g=ceil_int(totals.fiber_g),
    )


def to_display_extended(totals: RawTotals) -> ExtendedNutritionTotals:
    """Convert precise internal totals to the extended shape used ONLY by
    daily/weekly summaries - includes sugar/saturated_fat/sodium."""
    return ExtendedNutritionTotals(
        kcal=ceil_int(totals.kcal),
        protein_g=ceil_int(totals.protein_g),
        carbs_g=ceil_int(totals.carbs_g),
        fat_g=ceil_int(totals.fat_g),
        fiber_g=ceil_int(totals.fiber_g),
        sugar_g=ceil_int(totals.sugar_g),
        countable_sugar_g=ceil_int(totals.countable_sugar_g),
        saturated_fat_g=ceil_int(totals.saturated_fat_g),
        sodium_mg=ceil_int(totals.sodium_mg),
    )


SALT_TO_SODIUM_FACTOR = Decimal("2.5")  # salt(g) = sodium(g) x 2.5, per EU regulation


def salt_g_to_sodium_mg(salt_g: Decimal) -> Decimal:
    """
    Convert EU-label salt (grams) to our canonical sodium_mg_100g unit.
    This MUST run before any OCR-extracted or manually-entered salt value
    reaches an ItemCreate/ItemUpdate payload - the DB only ever stores
    sodium, never salt, so this conversion happens exactly once, at the
    boundary where label data becomes a stored value. Never store a raw
    "salt" number directly into sodium_mg_100g.
    """
    sodium_g = salt_g / SALT_TO_SODIUM_FACTOR
    return sodium_g * Decimal("1000")


def sodium_mg_to_salt_g(sodium_mg: Decimal) -> Decimal:
    """Inverse - useful if we ever want to display salt instead of sodium
    somewhere (e.g. showing EU-familiar units back to the user)."""
    sodium_g = sodium_mg / Decimal("1000")
    return sodium_g * SALT_TO_SODIUM_FACTOR


def resolve_grams(quantity: Decimal, serving_size: ServingSize | None) -> Decimal:
    """
    If a serving_size is given, quantity = number of that serving (e.g.
    "2 slices"), so grams = quantity * serving_size.weight_g. Otherwise
    quantity is interpreted as grams directly.
    """
    if serving_size is not None:
        return quantity * serving_size.weight_g
    return quantity


def compute_item_totals(
    item: Item, quantity: Decimal, serving_size: ServingSize | None = None
) -> RawTotals:
    grams = resolve_grams(quantity, serving_size)
    factor = grams / Decimal("100")
    sugar_g = (item.sugar_100g or ZERO) * factor

    if item.counts_as_added_sugar is not None:
        # Manual override wins outright, regardless of origin -- see
        # design discussion: "my third highest ranking added sugar
        # source is frozen berry mix... this is silly". A whole food
        # sold as a scanned/barcoded product (frozen fruit, dried fruit)
        # has no reliable automatic signal to catch, so this is a
        # deliberate per-item escape hatch rather than a smarter
        # heuristic.
        countable_sugar_g = sugar_g if item.counts_as_added_sugar else ZERO
    else:
        # Raw USDA-import ingredients (e.g. a banana) don't count toward
        # "countable" sugar - see RawTotals.countable_sugar_g's doc
        # comment. Everything else (scanned products, manually-entered
        # items, OCR-assisted) does, since a packaged food's sugar figure
        # could plausibly include added sugar and we have no finer-
        # grained label data to separate it out.
        countable_sugar_g = ZERO if item.origin == "usda_import" else sugar_g

    return RawTotals(
        kcal=(item.kcal_100g or ZERO) * factor,
        protein_g=(item.protein_100g or ZERO) * factor,
        carbs_g=(item.carbs_100g or ZERO) * factor,
        fat_g=(item.fat_100g or ZERO) * factor,
        fiber_g=(item.fiber_100g or ZERO) * factor,
        sugar_g=sugar_g,
        countable_sugar_g=countable_sugar_g,
        saturated_fat_g=(item.saturated_fat_100g or ZERO) * factor,
        sodium_mg=(item.sodium_mg_100g or ZERO) * factor,
    )


def compute_recipe_totals(recipe: Recipe) -> RawTotals:
    """Sum across all recipe_ingredients - the whole recipe, all servings."""
    total = ZERO_TOTALS
    for ri in recipe.ingredients:
        total = total + compute_item_totals(ri.item, ri.quantity, ri.serving_size)
    return total


def compute_recipe_totals_for_quantity(recipe: Recipe, quantity: Decimal) -> RawTotals:
    """
    `quantity` here = number of recipe servings consumed (e.g. 1.5 servings
    of a 4-serving recipe). Whole-recipe totals / recipe.servings * quantity.
    """
    whole = compute_recipe_totals(recipe)
    servings = recipe.servings or Decimal("1")
    return whole * (quantity / servings)