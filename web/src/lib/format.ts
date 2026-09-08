/** "200" instead of "200.0" for whole numbers, real decimals otherwise
 * - mirrors formatQuantity on the Android client (MealDetailViewModel.kt)
 * for the same reasoning: quantities like serving counts are
 * meaningful fractions, shown as-is. */
export function formatQuantity(value: number): string {
  return Number.isInteger(value) ? String(value) : String(value);
}

/** Always rounds UP to a whole number - mirrors formatGrams on the
 * Android client. Use for GRAM amounts specifically, never for a
 * serving count (see that function's own doc comment for why the
 * distinction matters - a gram figure from dividing across servings
 * that don't split evenly isn't something anyone measures by, unlike
 * a serving count fraction). */
export function formatGrams(value: number): string {
  return String(Math.ceil(value));
}

/** Parses a Decimal-as-string field from the API. Returns 0 for null/
 * undefined/unparseable rather than NaN, so callers can do arithmetic
 * without a defensive null-check at every use site. */
export function parseDecimal(value: string | null | undefined): number {
  if (value == null) return 0;
  const parsed = parseFloat(value);
  return Number.isNaN(parsed) ? 0 : parsed;
}
