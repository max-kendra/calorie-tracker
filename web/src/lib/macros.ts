import type { Log, MealType, NutritionTotals } from "@/api/types";

export const MEAL_TYPES: MealType[] = ["breakfast", "lunch", "dinner", "snack"];

export const MEAL_LABELS: Record<MealType, string> = {
  breakfast: "Breakfast",
  lunch: "Lunch",
  dinner: "Dinner",
  snack: "Snacks",
};

export const ZERO_TOTALS: NutritionTotals = { kcal: 0, protein_g: 0, carbs_g: 0, fat_g: 0, fiber_g: 0 };

export function addTotals(a: NutritionTotals, b: NutritionTotals): NutritionTotals {
  return {
    kcal: a.kcal + b.kcal,
    protein_g: a.protein_g + b.protein_g,
    carbs_g: a.carbs_g + b.carbs_g,
    fat_g: a.fat_g + b.fat_g,
    fiber_g: a.fiber_g + b.fiber_g,
  };
}

export function logTotals(log: Log): NutritionTotals {
  return {
    kcal: log.kcal_logged,
    protein_g: log.protein_g_logged,
    carbs_g: log.carbs_g_logged,
    fat_g: log.fat_g_logged,
    fiber_g: log.fiber_g_logged,
  };
}

export function sumTotals(logs: Log[]): NutritionTotals {
  return logs.reduce((acc, log) => addTotals(acc, logTotals(log)), ZERO_TOTALS);
}

/** date -> mealType -> logs, for building the week grid. Every date in
 * `dates` gets an entry (even with zero logs), and every meal type
 * within it gets an entry too, so components can iterate a fixed
 * shape instead of checking for missing keys everywhere. */
export function groupByDateAndMeal(logs: Log[], dates: string[]): Record<string, Record<MealType, Log[]>> {
  const result: Record<string, Record<MealType, Log[]>> = {};
  for (const date of dates) {
    result[date] = { breakfast: [], lunch: [], dinner: [], snack: [] };
  }
  for (const log of logs) {
    const day = result[log.date];
    if (day) {
      day[log.meal_type].push(log);
    }
  }
  return result;
}

/** The item's own display name - item logs and recipe logs are
 * denormalized onto the same Log row (see Log.item_name/recipe_name's
 * own doc comment on the backend for why both exist rather than one
 * shared field), never both set at once. */
export function logDisplayName(log: Log): string {
  return log.item_name ?? log.recipe_name ?? "Unknown";
}

/** Same servingSizeName-or-grams display convention as the Android
 * client's LogRow (see that composable's own doc comment) - a named
 * serving shows its name plus the gram equivalent; recipe logs show a
 * servings count instead of grams (quantity there means "servings
 * consumed", not grams - see LoggableEntryBase's own doc comment on
 * the backend); everything else is raw grams. */
export function logQuantityLabel(log: Log): string {
  const quantity = parseFloat(log.quantity);
  const quantityDisplay = Number.isInteger(quantity) ? String(quantity) : String(quantity);

  if (log.recipe_id != null) {
    return `${quantityDisplay} ${quantity === 1 ? "serving" : "servings"}`;
  }
  if (log.serving_size_name) {
    const weightG = log.serving_size_weight_g ? parseFloat(log.serving_size_weight_g) : null;
    const gramsSuffix = weightG != null ? ` (${Math.ceil(quantity * weightG)}g)` : "";
    return `${quantityDisplay} ${log.serving_size_name}${gramsSuffix}`;
  }
  return `${Math.ceil(quantity)}g`;
}
