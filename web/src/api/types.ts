export type MealType = "breakfast" | "lunch" | "dinner" | "snack";

/** Mirrors NutritionTotals from app/schemas.py - always whole numbers,
 * rounded UP for display on the backend already. Never re-round these
 * client-side. */
export interface NutritionTotals {
  kcal: number;
  protein_g: number;
  carbs_g: number;
  fat_g: number;
  fiber_g: number;
}

/** Mirrors ExtendedNutritionTotals - adds sugar/countable_sugar/
 * saturated_fat/sodium, only returned by the daily/weekly summary
 * endpoint. */
export interface ExtendedNutritionTotals extends NutritionTotals {
  sugar_g: number;
  countable_sugar_g: number;
  saturated_fat_g: number;
  sodium_mg: number;
}

/** One ingredient row from a recipe log's frozen snapshot - mirrors
 * LoggedRecipeIngredientOut. The macro fields (everything past kcal)
 * are optional/nullable: rows logged before the snapshot was widened
 * past kcal+grams have no historical value to backfill for those - see
 * that migration's own note in app/models.py. Treat null as "unknown",
 * never coerce to 0. */
export interface LoggedRecipeIngredient {
  item_id: number | null;
  item_name: string;
  serving_size_id: number | null;
  serving_size_name: string | null;
  serving_size_weight_g: string | null;
  quantity: string;
  grams: string;
  kcal: number;
  protein_g: number | null;
  carbs_g: number | null;
  fat_g: number | null;
  fiber_g: number | null;
  sugar_g: number | null;
  countable_sugar_g: number | null;
  saturated_fat_g: number | null;
  sodium_mg: number | null;
}

/** Mirrors LogOut - one logged item/recipe entry. `quantity` and
 * `serving_size_weight_g` are Decimal on the backend and come across
 * as strings, same convention the Android client uses - parse with
 * parseFloat when doing math, never trust JS's own number precision
 * for the raw value display (see src/lib/format.ts). */
export interface Log {
  id: number;
  date: string; // "YYYY-MM-DD"
  meal_type: MealType;
  item_id: number | null;
  recipe_id: number | null;
  serving_size_id: number | null;
  quantity: string;
  logged_at: string;
  kcal_logged: number;
  protein_g_logged: number;
  carbs_g_logged: number;
  fat_g_logged: number;
  fiber_g_logged: number;
  sugar_g_logged: number;
  countable_sugar_g_logged: number;
  saturated_fat_g_logged: number;
  sodium_mg_logged: number;
  item_name: string | null;
  recipe_name: string | null;
  image_path: string | null;
  serving_size_name: string | null;
  serving_size_weight_g: string | null;
  // Frozen per-ingredient breakdown for a RECIPE log - see backend
  // LoggedRecipeIngredient's own docstring. Empty for item-based logs.
  ingredients: LoggedRecipeIngredient[];
  // True for every log created after ingredient snapshotting shipped -
  // lets you tell "ingredients is empty because this is an item log /
  // a recipe with genuinely none" apart from "empty because this log
  // predates snapshotting and that history is gone".
  has_ingredient_snapshot: boolean;
  // The recipe's total servings YIELD at the time this was logged
  // (only set for recipe_id logs) - the denominator for `quantity`
  // (servings consumed) once the recipe's own servings count has
  // since been edited.
  recipe_servings_logged: string | null;
}

/** Mirrors DailySummary - GET /logs/summary/daily. */
export interface DailySummary {
  date: string;
  totals: ExtendedNutritionTotals;
}

/** Mirrors MealGoalSplitOut. */
export interface MealGoalSplit {
  meal_type: MealType;
  pct_of_kcal: string;
  computed_totals: NutritionTotals;
}

/** Mirrors GoalOut - GET /goals/active. All *_target fields are
 * Decimal on the backend (strings here); meal_splits' computed_totals
 * are pre-rounded ints. */
export interface Goal {
  id: number;
  start_date: string;
  end_date: string | null;
  kcal_target: string;
  protein_g_target: string;
  carbs_g_target: string;
  fat_g_target: string;
  fiber_g_target: string;
  meal_splits: MealGoalSplit[];
}
