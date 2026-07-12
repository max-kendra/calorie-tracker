"""
SQLAlchemy models — mirrors the schema in the design doc
(meal-tracker-design-doc.md, section 3). Keep these two in sync;
the design doc is the readable reference, this is the source of truth
Alembic diffs against.
"""

from sqlalchemy import (
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
    # Tracked for daily/weekly summaries only -- deliberately NOT surfaced
    # in the compact per-item/meal/recipe nutrition displays (NutritionTotals),
    # to avoid crowding the main tracker UI. See ExtendedNutritionTotals.
    sugar_100g = Column(Numeric, nullable=True)
    saturated_fat_100g = Column(Numeric, nullable=True)
    # Canonical unit is mg per 100g (matches USDA FoodData Central and
    # gives sensible precision for small amounts). EU labels show "salt"
    # in grams instead of sodium -- conversion is salt(g) = sodium(g) x
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

    serving_sizes = relationship("ServingSize", back_populates="item", cascade="all, delete-orphan")

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

    item = relationship("Item", back_populates="serving_sizes")


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
    instructions = Column(Text, nullable=True)  # typically unused for 'meal' type
    image_path = Column(String, nullable=True)
    servings = Column(Numeric, nullable=False, default=1)

    created_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at = Column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())

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
    quantity_g = Column(Numeric, nullable=False)

    recipe = relationship("Recipe", back_populates="ingredients")
    item = relationship("Item")


class Log(Base):
    """
    Actual, committed food log entries.

    IMPORTANT: macros are SNAPSHOTTED at write time (kcal_logged etc).
    If an item/recipe's macros are edited later, past logs must NOT
    change — historical days/weekly summaries reflect what was actually
    counted at the time. item_id/recipe_id are kept for traceability and
    re-logging, but the numbers that counted toward that day are frozen.
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

    kcal_logged = Column(Numeric, nullable=False)
    protein_g_logged = Column(Numeric, nullable=False)
    carbs_g_logged = Column(Numeric, nullable=False)
    fat_g_logged = Column(Numeric, nullable=False)
    fiber_g_logged = Column(Numeric, nullable=False)
    # Same snapshot-at-write-time integrity rule as the other macros --
    # frozen even if the source item is edited later. Not exposed in the
    # per-log API response (LogOut) to avoid crowding the tracker UI, but
    # summed for daily/weekly summaries (ExtendedNutritionTotals).
    sugar_g_logged = Column(Numeric, nullable=False, default=0)
    saturated_fat_g_logged = Column(Numeric, nullable=False, default=0)
    sodium_mg_logged = Column(Numeric, nullable=False, default=0)

    # Precise moment, for ordering/audit purposes.
    logged_at = Column(DateTime(timezone=True), server_default=func.now())

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


class MealPlan(Base):
    """
    Planned, not-yet-committed meals. NO macro snapshot — always reflects
    current item/recipe data until committed. The "commit to tracker"
    action copies matching rows into `logs`, computing + freezing the
    macro snapshot at that exact moment.
    """

    __tablename__ = "meal_plans"

    id = Column(Integer, primary_key=True)
    date = Column(Date, nullable=False)
    meal_type = Column(String, nullable=False)

    item_id = Column(Integer, ForeignKey("items.item_id"), nullable=True)
    recipe_id = Column(Integer, ForeignKey("recipes.recipe_id"), nullable=True)
    serving_size_id = Column(Integer, ForeignKey("serving_sizes.id"), nullable=True)
    quantity = Column(Numeric, nullable=False)

    __table_args__ = (
        CheckConstraint(
            "(item_id IS NOT NULL AND recipe_id IS NULL) OR "
            "(item_id IS NULL AND recipe_id IS NOT NULL)",
            name="ck_meal_plans_item_or_recipe",
        ),
        CheckConstraint(
            "meal_type IN ('breakfast', 'lunch', 'dinner', 'snack')",
            name="ck_meal_plans_meal_type",
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
    name = Column(String, nullable=False)  # e.g. "protein_per_kg_bodyweight"
    min_value = Column(Numeric, nullable=True)
    recommended_value = Column(Numeric, nullable=True)
    max_value = Column(Numeric, nullable=True)
    unit = Column(String, nullable=False)  # "g/kg", "g/kcal", etc.
    basis = Column(Text, nullable=True)  # source/reasoning, for traceability


class UserProfile(Base):
    """
    NOTE: bodyweight is NOT stored here — read live from Health Connect
    (Android). This table only holds things that aren't better sourced
    elsewhere.
    """

    __tablename__ = "user_profile"

    id = Column(Integer, primary_key=True)
    name = Column(String, nullable=True)
    profile_pic_path = Column(String, nullable=True)
    height_cm = Column(Numeric, nullable=True)
    age = Column(Integer, nullable=True)
    # 'estrogen' | 'testosterone' | 'other' | NULL — used only where
    # relevant for guideline calculations, not as a demographic label.
    primary_hormone = Column(String, nullable=True)
    activity_level = Column(String, nullable=True)
    # e.g. "Europe/Copenhagen" — used to resolve local `date` on logs
    timezone = Column(String, nullable=False, default="Europe/Copenhagen")

    updated_at = Column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())