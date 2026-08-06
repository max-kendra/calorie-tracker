"""
SQLAlchemy models — mirrors the schema in the design doc
(meal-tracker-design-doc.md, section 3). Keep these two in sync;
the design doc is the readable reference, this is the source of truth
Alembic diffs against.
"""

from sqlalchemy import (
    Boolean,
    CheckConstraint,
    Column,
    Date,
    DateTime,
    ForeignKey,
    Integer,
    Numeric,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import relationship
from sqlalchemy.sql import func

from app.database import Base


class Item(Base):
    """
    Covers both 'product' and 'ingredient' types (see `type` column).
    Fully self-managed — nothing auto-populated from external sources
    without explicit user review (see `origin`).
    """

    __tablename__ = "items"

    item_id = Column(Integer, primary_key=True)
    barcode = Column(String, unique=True, nullable=True)
    name = Column(String, nullable=False)
    brand = Column(String, nullable=True)
    image_path = Column(String, nullable=True)

    kcal_100g = Column(Numeric, nullable=True)
    protein_100g = Column(Numeric, nullable=True)
    carbs_100g = Column(Numeric, nullable=True)
    fat_100g = Column(Numeric, nullable=True)
    fiber_100g = Column(Numeric, nullable=True)
    # Tracked for daily/weekly summaries only - deliberately NOT surfaced
    # in the compact per-item/meal/recipe nutrition displays (NutritionTotals),
    # to avoid crowding the main tracker UI. See ExtendedNutritionTotals.
    sugar_100g = Column(Numeric, nullable=True)
    saturated_fat_100g = Column(Numeric, nullable=True)
    # Canonical unit is mg per 100g (matches USDA FoodData Central and
    # gives sensible precision for small amounts). EU labels show "salt"
    # in grams instead of sodium - conversion is salt(g) = sodium(g) x
    # 2.5, so sodium_mg_100g = salt_g_100g x 400. Handle that conversion
    # at data-entry time in the UI (e.g. an "enter as salt" toggle); not
    # done in the API.
    sodium_mg_100g = Column(Numeric, nullable=True)

    # 'product' | 'ingredient' — filter tag only, same fields either way.
    type = Column(String, nullable=False, default="product")
    # 'manual' | 'usda_import' | 'ocr_assisted' — provenance, always
    # user-reviewed before save regardless of origin.
    origin = Column(String, nullable=False, default="manual")

    created_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at = Column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())
    # Set at item creation AND whenever this item is actually logged
    # (see design discussion) -- backs "recently logged" ordering in the
    # search/recent list, distinct from updated_at (catalog-edit
    # recency). Nullable in principle, but every row gets this set one
    # way or another (creation, or the backfill migration for
    # pre-existing rows), so in practice it's never actually null.
    last_logged_at = Column(DateTime(timezone=True), nullable=True)
    # Remembers the quantity/serving this item was last logged with (see
    # last_logged_at's doc comment for the same "why this needs to live
    # on the row itself, not just in client memory" reasoning) -- lets
    # the quantity/serving picker default to whatever was actually used
    # last time, across ANY meal/day/app session, rather than a flat
    # 100g every time. None serving_size_id = grams directly, same dual
    # semantics as everywhere else quantity is stored in this app.
    last_logged_quantity = Column(Numeric, nullable=True)
    last_logged_serving_size_id = Column(Integer, ForeignKey("serving_sizes.id"), nullable=True)
    # Whether this item's sugar counts toward "added sugar" tracking -
    # a plain explicit flag, set per item (previously auto-guessed from
    # origin; dropped, see design discussion: "product means it has a
    # barcode, which frozen berries do" - origin/type never reliably
    # distinguished a whole food from an added-sugar product). NULL and
    # False behave identically (not counted). See compute_item_totals
    # for where this is actually applied.
    counts_as_added_sugar = Column(Boolean, nullable=True)

    serving_sizes = relationship(
        "ServingSize", back_populates="item", cascade="all, delete-orphan", foreign_keys="ServingSize.item_id"
    )

    __table_args__ = (
        CheckConstraint("type IN ('product', 'ingredient')", name="ck_items_type"),
        CheckConstraint(
            "origin IN ('manual', 'usda_import', 'ocr_assisted')", name="ck_items_origin"
        ),
    )


class ServingSize(Base):
    """
    An item's list of named serving sizes (e.g. "slice" -> 37.5g).
    Modeled as a child table rather than an array column so it stays
    queryable/joinable normally.
    """

    __tablename__ = "serving_sizes"

    id = Column(Integer, primary_key=True)
    item_id = Column(Integer, ForeignKey("items.item_id"), nullable=False)
    name = Column(String, nullable=False)  # e.g. "slice", "cup", "label serving"
    weight_g = Column(Numeric, nullable=False)

    item = relationship("Item", back_populates="serving_sizes", foreign_keys=[item_id])


class RawIngredientReference(Base):
    """
    Local cache of USDA FoodData Central lookups. Never joined directly
    into logs/recipes/meal_plans — selecting a result IMPORTS it into
    `items` (origin='usda_import'), which is what actually gets referenced
    elsewhere.
    """

    __tablename__ = "raw_ingredient_reference"

    id = Column(Integer, primary_key=True)
    fdc_id = Column(String, nullable=True)  # USDA FoodData Central ID
    name = Column(String, nullable=False)
    kcal_100g = Column(Numeric, nullable=True)
    protein_100g = Column(Numeric, nullable=True)
    carbs_100g = Column(Numeric, nullable=True)
    fat_100g = Column(Numeric, nullable=True)
    fiber_100g = Column(Numeric, nullable=True)
    fetched_at = Column(DateTime(timezone=True), server_default=func.now())


class Recipe(Base):
    """
    Covers both 'recipe' and 'meal' types (see `recipe_type`). A Meal is
    a recipe with recipe_type='meal' and servings=1, created via the star
    icon on a Journal meal card — snapshotting the currently-logged items
    into recipe_ingredients, so it stays editable afterward like any recipe.
    """

    __tablename__ = "recipes"

    recipe_id = Column(Integer, primary_key=True)
    name = Column(String, nullable=False)
    recipe_type = Column(String, nullable=False, default="recipe")  # 'recipe' | 'meal'
    instructions = Column(Text, nullable=True)  # available to both recipe_type values (see design discussion)
    source_url = Column(String, nullable=True)  # optional link to where this recipe/meal originally came from
    image_path = Column(String, nullable=True)
    servings = Column(Numeric, nullable=False, default=1)

    created_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at = Column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())
    # Set at recipe creation AND whenever this recipe/meal is actually
    # logged (see design discussion, same reasoning as Item's own
    # last_logged_at) -- backs "recently logged" ordering in the
    # unfiltered recipe/meal list, distinct from updated_at (catalog-
    # edit recency).
    last_logged_at = Column(DateTime(timezone=True), nullable=True)

    ingredients = relationship(
        "RecipeIngredient", back_populates="recipe", cascade="all, delete-orphan"
    )

    __table_args__ = (
        CheckConstraint("recipe_type IN ('recipe', 'meal')", name="ck_recipes_type"),
    )


class RecipeIngredient(Base):
    """
    Links a recipe to the items it's made of. Recipe totals are always
    computed live (SUM of item macros x quantity) — never denormalized —
    so editing an item's macros automatically flows through to every
    recipe/meal using it.
    """

    __tablename__ = "recipe_ingredients"

    recipe_id = Column(Integer, ForeignKey("recipes.recipe_id"), primary_key=True)
    item_id = Column(Integer, ForeignKey("items.item_id"), primary_key=True)
    # None = quantity is grams directly; set = quantity is a multiplier
    # of that ServingSize's weight_g -- same dual semantics as
    # LoggableEntryBase (see that schema's docstring), added so an
    # ingredient originally entered as "2 pancakes" can still be shown
    # that way later, rather than only ever remembering "150g".
    serving_size_id = Column(Integer, ForeignKey("serving_sizes.id"), nullable=True)
    quantity = Column(Numeric, nullable=False)

    recipe = relationship("Recipe", back_populates="ingredients")
    item = relationship("Item")
    serving_size = relationship("ServingSize")


class LoggedRecipeIngredient(Base):
    """
    Frozen per-log snapshot of one ingredient in a RECIPE log, taken once
    at create_log time and never touched again - the recipe-log
    equivalent of item_id/serving_size_id/quantity/kcal_logged on `logs`
    itself, just one level deeper.

    RecipeIngredient (the recipe's CURRENT composition) is fully live -
    editing a recipe's ingredients later has no history of its own, so
    without this table there was no way to answer "what did this recipe
    log actually consist of" for anything but the moment you looked,
    which silently drifted every time the recipe changed (see design
    discussion: changing a recipe's name/ingredients was retroactively
    changing what old logs of it appeared to contain).

    item_id/serving_size_id are kept for traceability only (ON DELETE
    SET NULL - an ingredient being deleted from the catalog shouldn't
    delete history of it having been eaten). Display must always prefer
    item_name_logged/serving_size_name_logged/grams_logged/kcal_logged,
    never re-resolve through those ids live.
    """

    __tablename__ = "logged_recipe_ingredients"

    id = Column(Integer, primary_key=True)
    log_id = Column(Integer, ForeignKey("logs.id", ondelete="CASCADE"), nullable=False, index=True)

    item_id = Column(Integer, ForeignKey("items.item_id", ondelete="SET NULL"), nullable=True)
    item_name_logged = Column(String, nullable=False)

    serving_size_id = Column(Integer, ForeignKey("serving_sizes.id", ondelete="SET NULL"), nullable=True)
    serving_size_name_logged = Column(String, nullable=True)
    serving_size_weight_g_logged = Column(Numeric, nullable=True)

    # Same dual semantics as logs.quantity/RecipeIngredient.quantity: grams
    # directly if no serving, else a multiplier of
    # serving_size_weight_g_logged.
    quantity = Column(Numeric, nullable=False)
    # Resolved actual grams for this ingredient at the RECIPE's logged
    # quantity as a whole (already scaled by however many servings were
    # consumed) - frozen so the client can display it directly without
    # redoing serving-size math against catalog data that may have since
    # changed or been deleted.
    grams_logged = Column(Numeric, nullable=False)
    kcal_logged = Column(Numeric, nullable=False)
    # Full per-ingredient breakdown, same "frozen at create_log time"
    # discipline as everything else here - not a new computation,
    # compute_recipe_ingredient_snapshot already builds a full
    # RawTotals per ingredient (needed to sum into the recipe log's own
    # aggregate *_logged columns), this just keeps the rest of it
    # instead of discarding everything but kcal. Nullable because
    # existing rows from before this was added have no historical
    # value to backfill - only kcal_logged/grams_logged survive for
    # those (see the migration's own note).
    protein_g_logged = Column(Numeric, nullable=True)
    carbs_g_logged = Column(Numeric, nullable=True)
    fat_g_logged = Column(Numeric, nullable=True)
    fiber_g_logged = Column(Numeric, nullable=True)
    sugar_g_logged = Column(Numeric, nullable=True)
    countable_sugar_g_logged = Column(Numeric, nullable=True)
    saturated_fat_g_logged = Column(Numeric, nullable=True)
    sodium_mg_logged = Column(Numeric, nullable=True)

    item = relationship("Item")
    serving_size = relationship("ServingSize")


class Log(Base):
    """
    Actual, committed food log entries.

    IMPORTANT: macros are SNAPSHOTTED at write time (kcal_logged etc).
    If an item/recipe's macros are edited later, past logs must NOT
    change — historical days/weekly summaries reflect what was actually
    counted at the time. item_id/recipe_id are kept for traceability and
    re-logging, but the numbers that counted toward that day are frozen.

    The DISPLAY identity of what was logged is frozen the same way, via
    item_name_logged/recipe_name_logged/image_path_logged - previously
    these were resolved live against the current Item/Recipe on every
    read, so renaming a recipe (or changing its photo) retroactively
    changed the label on every past log referencing it. NULL on an
    existing pre-migration row means "no frozen value available, fall
    back to a live lookup" - every row created from here on always has
    these populated (see create_log).
    """

    __tablename__ = "logs"

    id = Column(Integer, primary_key=True)

    # Plain DATE (no tz) — resolved from logged_at + user's local timezone
    # at write time. This is what daily/weekly summaries group by.
    date = Column(Date, nullable=False)
    meal_type = Column(String, nullable=False)  # 'breakfast' | 'lunch' | 'dinner' | 'snack'

    item_id = Column(Integer, ForeignKey("items.item_id"), nullable=True)
    recipe_id = Column(Integer, ForeignKey("recipes.recipe_id"), nullable=True)
    serving_size_id = Column(Integer, ForeignKey("serving_sizes.id"), nullable=True)
    quantity = Column(Numeric, nullable=False)

    item_name_logged = Column(String, nullable=True)
    recipe_name_logged = Column(String, nullable=True)
    image_path_logged = Column(String, nullable=True)
    # Freezes recipe.servings (the recipe's total YIELD at log time,
    # e.g. "made 4 servings") - only meaningful for recipe_id logs.
    # quantity (below) already freezes how many of those servings YOU
    # consumed; this is the other half of that fraction, needed to
    # answer "1.5 servings out of how many total" for an old log once
    # the recipe's own servings count has since been edited. Doesn't
    # affect the nutrition math (kcal_logged etc and the ingredient
    # snapshot are already absolute, computed once at creation time) -
    # this is purely so that ratio remains answerable historically.
    recipe_servings_logged = Column(Numeric, nullable=True)
    # True for every log created after the name/ingredient-freeze feature
    # shipped (item AND recipe logs alike) - False only for pre-existing
    # rows migrated in before it existed. Exists so the client can tell
    # "this recipe log's `ingredients` is empty because the recipe
    # genuinely had none" apart from "empty because this log predates
    # ingredient snapshotting and that history is simply gone" - both
    # look identical as an empty list otherwise. Not meaningful on its
    # own for item logs (they never have an ingredients list either
    # way), but set True on them too rather than left ambiguous, since
    # nothing about their own data is actually missing.
    has_ingredient_snapshot = Column(Boolean, nullable=False, server_default="false")

    kcal_logged = Column(Numeric, nullable=False)
    protein_g_logged = Column(Numeric, nullable=False)
    carbs_g_logged = Column(Numeric, nullable=False)
    fat_g_logged = Column(Numeric, nullable=False)
    fiber_g_logged = Column(Numeric, nullable=False)
    # Same snapshot-at-write-time integrity rule as the other macros -
    # frozen even if the source item is edited later. Not exposed in the
    # per-log API response (LogOut) to avoid crowding the tracker UI, but
    # summed for daily/weekly summaries (ExtendedNutritionTotals).
    sugar_g_logged = Column(Numeric, nullable=False, default=0)
    # Sugar EXCLUDING raw USDA-import-origin ingredients (see design
    # discussion) -- what the weekly summary's sugar tracking actually
    # sums, instead of sugar_g_logged, since naturally-occurring fruit
    # sugar isn't what added-sugar dietary guidance targets. Frozen at
    # write time same as every other _logged column.
    countable_sugar_g_logged = Column(Numeric, nullable=False, default=0)
    saturated_fat_g_logged = Column(Numeric, nullable=False, default=0)
    sodium_mg_logged = Column(Numeric, nullable=False, default=0)

    # Precise moment, for ordering/audit purposes.
    logged_at = Column(DateTime(timezone=True), server_default=func.now())

    serving_size = relationship("ServingSize")
    # Per-log recipe ingredient snapshot (empty for item-based logs, and
    # for recipe logs created before this feature existed - see
    # LoggedRecipeIngredient's own docstring). cascade="all, delete-orphan"
    # so deleting a log cleans up its snapshot rows without needing a
    # separate query.
    ingredients = relationship(
        "LoggedRecipeIngredient", cascade="all, delete-orphan", order_by="LoggedRecipeIngredient.id"
    )

    __table_args__ = (
        CheckConstraint(
            "(item_id IS NOT NULL AND recipe_id IS NULL) OR "
            "(item_id IS NULL AND recipe_id IS NOT NULL)",
            name="ck_logs_item_or_recipe",
        ),
        CheckConstraint(
            "meal_type IN ('breakfast', 'lunch', 'dinner', 'snack')",
            name="ck_logs_meal_type",
        ),
    )


class Goal(Base):
    """
    Overall caloric/macro targets. Dated rather than a single row, so
    history of changing goals (cutting/bulking/maintenance) is preserved
    and weekly summaries can reference the goal that was active at the time.
    """

    __tablename__ = "goals"

    id = Column(Integer, primary_key=True)
    start_date = Column(Date, nullable=False)
    end_date = Column(Date, nullable=True)  # NULL = currently active

    kcal_target = Column(Numeric, nullable=False)
    protein_g_target = Column(Numeric, nullable=False)
    carbs_g_target = Column(Numeric, nullable=False)
    fat_g_target = Column(Numeric, nullable=False)
    fiber_g_target = Column(Numeric, nullable=False)

    meal_splits = relationship(
        "MealGoalSplit", back_populates="goal", cascade="all, delete-orphan"
    )


class MealGoalSplit(Base):
    """
    Per-meal targets are NEVER stored as absolute numbers — always a
    percentage of the overall goal, computed at read time and rounded to
    int for display. UI enforces all splits for a goal sum to 100%; that's
    an application-layer validation, not a DB constraint (a partial edit
    mid-save shouldn't be rejected by the database itself).
    """

    __tablename__ = "meal_goal_splits"

    id = Column(Integer, primary_key=True)
    goal_id = Column(Integer, ForeignKey("goals.id"), nullable=False)
    meal_type = Column(String, nullable=False)
    pct_of_kcal = Column(Numeric, nullable=False)  # e.g. 25.0 for 25%; macros follow same pct

    goal = relationship("Goal", back_populates="meal_splits")

    __table_args__ = (
        CheckConstraint(
            "meal_type IN ('breakfast', 'lunch', 'dinner', 'snack')",
            name="ck_meal_goal_splits_meal_type",
        ),
        UniqueConstraint("goal_id", "meal_type", name="uq_meal_goal_splits_goal_meal"),
    )


class PhysiologicalGuideline(Base):
    """
    Population-level reference points (e.g. "protein 0.8-2.2 g/kg
    bodyweight"), kept separate from `goals`. The warning system compares
    the active goal against these ranges — not the daily log directly, to
    avoid false warnings on days the user simply ate less than their goal.
    """

    __tablename__ = "physiological_guidelines"

    id = Column(Integer, primary_key=True)
    name = Column(String, nullable=False, unique=True)  # e.g. "protein_per_kg_bodyweight"
    min_value = Column(Numeric, nullable=True)
    recommended_value = Column(Numeric, nullable=True)
    max_value = Column(Numeric, nullable=True)
    unit = Column(String, nullable=False)  # "g/kg", "g/kcal", etc.
    basis = Column(Text, nullable=True)  # source/reasoning, for traceability


class UserProfile(Base):
    """
    NOTE: bodyweight is a manual stopgap here (weight_kg) until Health
    Connect integration is built on the Android side - at that point,
    this field should be treated as a fallback/cache, with Health
    Connect's latest reading preferred when available (see design doc's
    original intent for bodyweight sourcing).
    """

    __tablename__ = "user_profile"

    id = Column(Integer, primary_key=True)
    name = Column(String, nullable=True)
    profile_pic_path = Column(String, nullable=True)
    height_cm = Column(Integer, nullable=True)
    age = Column(Integer, nullable=True)
    # Manual stopgap - see class docstring. Nullable since not every
    # user will have entered it yet, especially before this existed.
    weight_kg = Column(Numeric, nullable=True)
    # Weight goal - purely user-entered reference points for the
    # Profile screen's "start -> current -> goal" summary and the weight
    # graph's goal line. Unlike weight_kg above, these are NOT meant to
    # be superseded by Health Connect - "starting weight" in particular
    # is inherently a fixed historical value (whatever it was when the
    # user set this goal), not a live reading. current weight for that
    # same summary comes from Health Connect at read time, not from a
    # stored column.
    starting_weight_kg = Column(Numeric, nullable=True)
    goal_weight_kg = Column(Numeric, nullable=True)
    # 'estrogen' | 'testosterone' | NULL — used only where relevant for
    # guideline calculations, not as a demographic label. A DB row could
    # still contain a legacy 'other' from before that option was
    # removed, but no profile actually had it set at removal time.
    primary_hormone = Column(String, nullable=True)
    activity_level = Column(String, nullable=True)
    # 'lose' | 'maintain' | 'gain' - drives the kcal goal calculation
    # (see routers/user_profile.py). Stored on the profile since it's a
    # standing orientation, not a one-off calculation parameter.
    goal_type = Column(String, nullable=True)
    # e.g. "Europe/Copenhagen" — used to resolve local `date` on logs
    timezone = Column(String, nullable=False, default="Europe/Copenhagen")

    updated_at = Column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())