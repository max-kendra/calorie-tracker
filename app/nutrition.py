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
    # Sugar counted toward "added sugar" tracking, per each item's own
    # explicit counts_as_added_sugar flag (see design discussion: "keep
    # an eye on metrics" flagging bananas as a top sugar source, when
    # added-sugar dietary guidance is about added/free sugars
    # specifically, not sugar naturally occurring in whole foods).
    # Packaged foods only ever give "carbs, of which sugars" with no
    # further breakdown, so there's no way to derive this from the
    # macros alone - it has to be set per item. Unset (None) is treated
    # the same as False (not counted) - no origin/type-based guessing
    # (see Item.countsAsAddedSugar's own doc comment for why that was
    # dropped: neither field reliably distinguishes a whole food from
    # an added-sugar product, since a barcode belongs to both equally).
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

    # No more origin-based guessing (see design discussion: "product
    # means it has a barcode, which frozen berries do" - origin/type
    # were never a reliable signal for whether a food has added sugar
    # in the first place, since a barcode can belong to frozen berries
    # just as easily as a candy bar). counts_as_added_sugar is now a
    # plain, explicit per-item flag set at creation time - unset (None,
    # the same as False) simply means "not counted," full stop, rather
    # than triggering any inference.
    countable_sugar_g = sugar_g if item.counts_as_added_sugar else ZERO

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


def compute_recipe_ingredient_snapshot(
    recipe: Recipe, quantity: Decimal
) -> list[tuple["RecipeIngredient", RawTotals, Decimal, Decimal]]:
    """
    Per-ingredient breakdown at the given logged quantity (servings
    consumed) - each ingredient's RawTotals, resolved grams, AND its own
    quantity (the serving-multiplier-or-raw-grams value LoggedRecipeIngredient.
    quantity stores), all scaled by the same quantity/servings factor as
    compute_recipe_totals_for_quantity. Called once, at create_log time,
    to freeze into LoggedRecipeIngredient rows - NOT re-called on later
    quantity/serving edits to an existing log, since recipe.ingredients
    reflects the recipe's CURRENT (possibly since-changed) composition,
    not what it was when first logged. See LoggedRecipeIngredient's
    docstring for why this needs to be captured at all rather than
    resolved live.

    The scaled quantity is returned (not just totals/grams) so the
    ingredient's displayed WEIGHT matches its displayed MACROS - e.g. "1
    of 3 servings" of a recipe should show each ingredient at a third of
    its full-recipe amount, the same fraction its frozen kcal/protein/
    etc already reflect, rather than leaving the full-recipe quantity
    sitting next to already-scaled-down macros.
    """
    servings = recipe.servings or Decimal("1")
    factor = quantity / servings
    snapshot = []
    for ri in recipe.ingredients:
        totals = compute_item_totals(ri.item, ri.quantity, ri.serving_size) * factor
        grams = resolve_grams(ri.quantity, ri.serving_size) * factor
        scaled_quantity = ri.quantity * factor
        snapshot.append((ri, totals, grams, scaled_quantity))
    return snapshot