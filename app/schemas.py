from datetime import date, datetime
from decimal import Decimal
from typing import Literal, Optional

from pydantic import BaseModel, ConfigDict, Field

ItemType = Literal["product", "ingredient"]
ItemOrigin = Literal["manual", "usda_import", "ocr_assisted"]


class ServingSizeBase(BaseModel):
    name: str
    weight_g: Decimal


class ServingSizeCreate(ServingSizeBase):
    pass


class ServingSizeOut(ServingSizeBase):
    id: int
    item_id: int

    model_config = ConfigDict(from_attributes=True)


class ItemBase(BaseModel):
    name: str
    barcode: Optional[str] = None
    brand: Optional[str] = None
    image_path: Optional[str] = None

    kcal_100g: Optional[Decimal] = None
    protein_100g: Optional[Decimal] = None
    carbs_100g: Optional[Decimal] = None
    fat_100g: Optional[Decimal] = None
    fiber_100g: Optional[Decimal] = None
    # Tracked for daily/weekly summaries only -- not surfaced in the
    # compact per-item/recipe/log nutrition displays.
    sugar_100g: Optional[Decimal] = None
    saturated_fat_100g: Optional[Decimal] = None
    sodium_mg_100g: Optional[Decimal] = None

    type: ItemType = "product"
    origin: ItemOrigin = "manual"


class ItemCreate(ItemBase):
    pass


class ItemUpdate(BaseModel):
    """
    All fields optional — supports partial updates (e.g. correcting just
    kcal_100g after noticing a typo, per the "editable macros" requirement
    from the design doc). Changes here are picked up automatically by any
    recipe referencing this item, since recipe totals are computed live.
    """

    name: Optional[str] = None
    barcode: Optional[str] = None
    brand: Optional[str] = None
    image_path: Optional[str] = None

    kcal_100g: Optional[Decimal] = None
    protein_100g: Optional[Decimal] = None
    carbs_100g: Optional[Decimal] = None
    fat_100g: Optional[Decimal] = None
    fiber_100g: Optional[Decimal] = None
    sugar_100g: Optional[Decimal] = None
    saturated_fat_100g: Optional[Decimal] = None
    sodium_mg_100g: Optional[Decimal] = None

    type: Optional[ItemType] = None


class ItemOut(ItemBase):
    item_id: int
    created_at: datetime
    updated_at: datetime
    serving_sizes: list[ServingSizeOut] = Field(default_factory=list)

    model_config = ConfigDict(from_attributes=True)


class BarcodeScanResult(BaseModel):
    """
    Result of scanning an uploaded image for a barcode. If `item` is None,
    the barcode was decoded successfully but no matching item exists yet
    in our DB -- the client should pre-fill the Add Item form with
    `barcode` and let the user fill in the rest. If `barcode` itself is
    None, neither decoder could find anything in the image -- the client
    should fall back to manual barcode entry.

    IMPORTANT: real-world testing found decoders can return a WRONG
    barcode value that still looks structurally valid. `checksum_valid`
    being True is NOT proof of correctness (a garbled read can still pass
    its own checksum) -- the client MUST always display `barcode` to the
    user for visual confirmation against the physical package before
    using it to match or create an item. Never auto-proceed silently.
    """

    barcode: Optional[str] = None
    decoder_used: Optional[str] = None
    checksum_valid: Optional[bool] = None
    item: Optional[ItemOut] = None


class UsdaMacros(BaseModel):
    """
    Best-effort normalized macros from a USDA FoodData Central entry.
    Fields are omitted (not zero) when FDC didn't report them -- the
    client should treat an absent field as "unknown," pre-filling that
    part of the Add Item form blank rather than with a misleading 0.
    """

    kcal_100g: Optional[Decimal] = None
    protein_100g: Optional[Decimal] = None
    carbs_100g: Optional[Decimal] = None
    fat_100g: Optional[Decimal] = None
    fiber_100g: Optional[Decimal] = None
    sugar_100g: Optional[Decimal] = None
    saturated_fat_100g: Optional[Decimal] = None
    sodium_mg_100g: Optional[Decimal] = None


class UsdaFoodSummaryOut(BaseModel):
    fdc_id: int
    description: str
    data_type: str
    brand_owner: Optional[str] = None
    macros: UsdaMacros


class UsdaFoodDetailOut(BaseModel):
    fdc_id: int
    description: str
    data_type: str
    macros: UsdaMacros


class OcrMacros(BaseModel):
    """Same shape as UsdaMacros -- fields absent (not zero) when OCR
    didn't confidently extract them. Client pre-fills the Add Item form
    with whatever was found and leaves the rest blank for manual entry."""

    kcal_100g: Optional[Decimal] = None
    protein_100g: Optional[Decimal] = None
    carbs_100g: Optional[Decimal] = None
    fat_100g: Optional[Decimal] = None
    fiber_100g: Optional[Decimal] = None
    sugar_100g: Optional[Decimal] = None
    saturated_fat_100g: Optional[Decimal] = None
    sodium_mg_100g: Optional[Decimal] = None


class OcrScanResult(BaseModel):
    """
    Result of OCR-scanning a nutrition label photo. NEVER written to our
    DB directly -- same review-before-save pattern as barcode scanning
    and USDA import. The client pre-fills the Add Item form with `macros`
    and lets the user correct anything before it's actually saved via the
    normal POST /items call.

    `per_100g_confirmed` being False means we couldn't confirm the label
    values are per-100g (vs. per-serving) -- the user should double check
    this specifically, since we don't currently attempt per-serving-to-
    per-100g conversion.

    `detected_language` being null means we couldn't confidently identify
    which language's label format this is -- macros will be empty and
    the user needs to enter everything manually.
    """

    raw_text: str
    detected_language: Optional[str] = None
    per_100g_confirmed: bool = False
    macros: OcrMacros


class ProductPhotoScanResult(BaseModel):
    """
    Result of uploading a (cropped) photo of the product itself -- the
    step BEFORE the nutrition-label photo in the Add Item flow. Two
    separate things happen here:

    1. The image is saved to disk and `image_path` is returned so the
       client can carry it through and attach it to the item on save
       (POST /items with `image_path` set) -- this is the item's
       "package photo", same one shown later in My Foods/search results.
    2. We run OCR over it and take a best-effort guess at the product's
       name and brand from whatever text we found, since packaging
       almost always has both printed somewhere on the front.

    IMPORTANT -- `guessed_name`/`guessed_brand` are genuinely rough
    heuristics (longest line of text found = name, another prominent
    line = brand), NOT a confident structured extraction the way
    OcrScanResult's `macros` are for nutrition labels. There's no
    per-language keyword dictionary to anchor on here -- a product
    package's front doesn't have consistent field labels the way a
    nutrition table does. Client MUST show these as pre-filled but
    clearly editable text fields, never as an assumed-correct value --
    same review-before-save principle as everything else OCR-derived in
    this app.
    """

    image_path: str
    raw_text: str
    guessed_name: Optional[str] = None
    guessed_brand: Optional[str] = None


RecipeType = Literal["recipe", "meal"]


class RecipeIngredientCreate(BaseModel):
    item_id: int
    quantity_g: Decimal


class RecipeIngredientOut(BaseModel):
    item_id: int
    quantity_g: Decimal
    item_name: str  # denormalized for convenience — avoids a second lookup client-side

    model_config = ConfigDict(from_attributes=True)


class NutritionTotals(BaseModel):
    """
    API-facing, always whole numbers rounded UP for display (see
    app/nutrition.py for the rounding policy — internal math stays in
    precise Decimal via RawTotals, only converted to this shape right
    before a response is built).

    Deliberately compact — used on items/recipes/logs/meal_plans. Does
    NOT include sugar/saturated_fat, to keep those displays uncluttered;
    see ExtendedNutritionTotals for where those surface instead.
    """

    kcal: int
    protein_g: int
    carbs_g: int
    fat_g: int
    fiber_g: int


class ExtendedNutritionTotals(NutritionTotals):
    """
    Same as NutritionTotals plus sugar_g/saturated_fat_g. Used ONLY by the
    daily/weekly summary endpoint — deliberately kept out of the compact
    per-item/recipe/log displays so the main tracker UI doesn't get
    crowded with numbers most people don't need to see every time they
    log something, while still being trackable in aggregate.
    """

    sugar_g: int
    saturated_fat_g: int
    sodium_mg: int


class RecipeBase(BaseModel):
    name: str
    recipe_type: RecipeType = "recipe"
    instructions: Optional[str] = None
    image_path: Optional[str] = None
    servings: Decimal = Decimal("1")


class RecipeCreate(RecipeBase):
    ingredients: list[RecipeIngredientCreate] = Field(default_factory=list)


class RecipeUpdate(BaseModel):
    """Partial update for recipe metadata. Ingredients are managed via
    their own endpoints (add/remove/replace-all), not through this."""

    name: Optional[str] = None
    recipe_type: Optional[RecipeType] = None
    instructions: Optional[str] = None
    image_path: Optional[str] = None
    servings: Optional[Decimal] = None


class RecipeOut(RecipeBase):
    recipe_id: int
    created_at: datetime
    updated_at: datetime
    ingredients: list[RecipeIngredientOut] = Field(default_factory=list)
    totals: NutritionTotals  # for the whole recipe (all servings combined)
    totals_per_serving: NutritionTotals

    model_config = ConfigDict(from_attributes=True)


MealType = Literal["breakfast", "lunch", "dinner", "snack"]


class LoggableEntryBase(BaseModel):
    """
    Shared shape between logs and meal_plans: reference either an item or
    a recipe (never both/neither — enforced both at the DB CHECK
    constraint level and here), plus quantity and optional serving size.

    quantity semantics:
      - item + serving_size_id set: quantity = number of that serving
        (e.g. 2 slices) — grams = quantity * serving_size.weight_g
      - item + no serving_size_id: quantity = grams directly
      - recipe: quantity = number of recipe servings consumed
    """

    date: date
    meal_type: MealType
    item_id: Optional[int] = None
    recipe_id: Optional[int] = None
    serving_size_id: Optional[int] = None
    quantity: Decimal


class LogCreate(LoggableEntryBase):
    pass


class LogOut(LoggableEntryBase):
    id: int
    logged_at: datetime
    # Snapshotted as precise Decimal in the DB at write time (see design
    # doc — frozen from here on, regardless of later edits to the source
    # item/recipe). Rounded UP to whole numbers here, at the API/display
    # boundary only — the stored value stays exact for accurate summaries.
    kcal_logged: int
    protein_g_logged: int
    carbs_g_logged: int
    fat_g_logged: int
    fiber_g_logged: int
    # Denormalized for convenient display, not stored on the row itself.
    item_name: Optional[str] = None
    recipe_name: Optional[str] = None

    model_config = ConfigDict(from_attributes=True)


class DailySummary(BaseModel):
    date: date
    totals: ExtendedNutritionTotals


class MealPlanCreate(LoggableEntryBase):
    pass


class MealPlanUpdate(BaseModel):
    date: Optional[date] = None
    meal_type: Optional[MealType] = None
    quantity: Optional[Decimal] = None
    serving_size_id: Optional[int] = None


class MealPlanOut(LoggableEntryBase):
    id: int
    # NOT snapshotted — always reflects current item/recipe data until
    # committed. Computed fresh on every read.
    computed_totals: NutritionTotals
    item_name: Optional[str] = None
    recipe_name: Optional[str] = None

    model_config = ConfigDict(from_attributes=True)


class CommitRange(BaseModel):
    """Commit all meal_plans in [start_date, end_date] (inclusive) into
    logs. Use the same date for both to commit a single day."""

    start_date: date
    end_date: date


class CommitResult(BaseModel):
    committed_count: int
    log_ids: list[int]


class MealGoalSplitIn(BaseModel):
    meal_type: MealType
    pct_of_kcal: Decimal


class MealGoalSplitOut(BaseModel):
    meal_type: MealType
    pct_of_kcal: Decimal
    # Derived, computed live from the parent goal's targets x this
    # percentage -- never stored. Rounded for display per our policy.
    computed_totals: NutritionTotals

    model_config = ConfigDict(from_attributes=True)


class GoalBase(BaseModel):
    start_date: date
    end_date: Optional[date] = None
    kcal_target: Decimal
    protein_g_target: Decimal
    carbs_g_target: Decimal
    fat_g_target: Decimal
    fiber_g_target: Decimal


class GoalCreate(GoalBase):
    """
    Creating a new goal automatically closes the previously active one
    (sets its end_date to the day before this goal's start_date) --
    there's only ever one active (end_date IS NULL) goal at a time.
    Defaults to an even 25/25/25/25 meal split unless overridden.
    """

    meal_splits: Optional[list[MealGoalSplitIn]] = None


class GoalUpdate(BaseModel):
    kcal_target: Optional[Decimal] = None
    protein_g_target: Optional[Decimal] = None
    carbs_g_target: Optional[Decimal] = None
    fat_g_target: Optional[Decimal] = None
    fiber_g_target: Optional[Decimal] = None


class GoalOut(GoalBase):
    id: int
    meal_splits: list[MealGoalSplitOut] = Field(default_factory=list)

    model_config = ConfigDict(from_attributes=True)


class MealGoalSplitsUpdate(BaseModel):
    """
    Bulk replace all of a goal's meal splits at once -- matches the
    "Meal calorie goal" screen from the design doc, where all meals are
    edited together on one screen with a hard validation gate: percentages
    must sum to exactly 100, enforced here as well as in the UI.
    """

    splits: list[MealGoalSplitIn]


PrimaryHormone = Literal["estrogen", "testosterone", "other"]


GoalType = Literal["lose", "maintain", "gain"]
ActivityLevel = Literal["sedentary", "light", "moderate", "active", "very_active"]


class UserProfileBase(BaseModel):
    name: Optional[str] = None
    profile_pic_path: Optional[str] = None
    height_cm: Optional[int] = None
    age: Optional[int] = None
    # Manual stopgap until Health Connect integration exists on Android --
    # see UserProfile model docstring.
    weight_kg: Optional[Decimal] = None
    # Fixed reference points for the Profile screen's weight-goal summary
    # -- see UserProfile model docstring for why these are NOT superseded
    # by Health Connect the way weight_kg conceptually is.
    starting_weight_kg: Optional[Decimal] = None
    goal_weight_kg: Optional[Decimal] = None
    primary_hormone: Optional[PrimaryHormone] = None
    activity_level: Optional[ActivityLevel] = None
    goal_type: Optional[GoalType] = None
    timezone: str = "Europe/Copenhagen"


class UserProfileUpdate(BaseModel):
    name: Optional[str] = None
    profile_pic_path: Optional[str] = None
    height_cm: Optional[int] = None
    age: Optional[int] = None
    weight_kg: Optional[Decimal] = None
    starting_weight_kg: Optional[Decimal] = None
    goal_weight_kg: Optional[Decimal] = None
    primary_hormone: Optional[PrimaryHormone] = None
    activity_level: Optional[ActivityLevel] = None
    goal_type: Optional[GoalType] = None
    timezone: Optional[str] = None


class UserProfileOut(UserProfileBase):
    id: int
    updated_at: datetime

    model_config = ConfigDict(from_attributes=True)


class KcalGoalCalculationResult(BaseModel):
    """
    All intermediate values are returned, not just the final number --
    lets the client show its work (BMR, then TDEE, then the goal
    adjustment) rather than presenting a single opaque figure.

    All values are whole integers -- fractional calories aren't
    meaningful to a user. recommended_kcal/kcal_low/kcal_high are further
    rounded to the nearest 25, since a number like "1636" is both false
    precision (the formula is only accurate to ~10% to begin with, which
    is a wider margin than a 25-kcal step) and awkward to actually track
    against day to day -- a round number is both more honest and more
    usable.

    kcal_low/kcal_high express the formula's own accuracy margin (~10%,
    per Frankenfield et al. 2005 -- see calculate_kcal_goal's docstring)
    as an honest range, rather than presenting recommended_kcal as if it
    were precise to the calorie.
    """

    bmr: int
    tdee: int
    recommended_kcal: int
    kcal_low: int
    kcal_high: int
    floor_applied: bool  # true if the 1500 kcal/day safety floor kicked in