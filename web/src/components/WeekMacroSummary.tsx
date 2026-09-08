import type { Goal, NutritionTotals } from "@/api/types";
import { parseDecimal } from "@/lib/format";
import { MACRO_COLORS } from "@/lib/colors";
import { MacroBar } from "./MacroBar";

interface WeekMacroSummaryProps {
  weekTotals: NutritionTotals;
  goal: Goal | undefined;
  daysInWeekSoFar: number;
}

/** Goal targets are PER DAY on the backend (see GoalBase's own fields -
 * kcal_target etc are daily targets, same figure the day view uses) -
 * multiplied here by how many days of the week have actually happened
 * so far, not by 7 flat, so a Wednesday check-in compares against
 * Mon-Wed's worth of budget rather than the whole week's, matching
 * how the Android app's own weekly summary screen handles this. */
export function WeekMacroSummary({ weekTotals, goal, daysInWeekSoFar }: WeekMacroSummaryProps) {
  if (!goal) return null;

  const kcalGoal = Math.round(parseDecimal(goal.kcal_target) * daysInWeekSoFar);
  const proteinGoal = Math.round(parseDecimal(goal.protein_g_target) * daysInWeekSoFar);
  const carbsGoal = Math.round(parseDecimal(goal.carbs_g_target) * daysInWeekSoFar);
  const fatGoal = Math.round(parseDecimal(goal.fat_g_target) * daysInWeekSoFar);
  const fiberGoal = Math.round(parseDecimal(goal.fiber_g_target) * daysInWeekSoFar);

  return (
    <div className="bg-white rounded-2xl shadow-sm p-4 mb-4">
      <div className="flex items-baseline justify-between mb-1">
        <h2 className="text-lg font-semibold text-gray-800">This week</h2>
        <span className="text-sm text-gray-500">
          {weekTotals.kcal} / {kcalGoal} Cal
        </span>
      </div>
      <div className="grid grid-cols-2 md:grid-cols-4 gap-x-6 gap-y-3 mt-3">
        <MacroBar label="Protein" eaten={weekTotals.protein_g} goal={proteinGoal} color={MACRO_COLORS.protein} />
        <MacroBar label="Fat" eaten={weekTotals.fat_g} goal={fatGoal} color={MACRO_COLORS.fat} />
        <MacroBar label="Carbs" eaten={weekTotals.carbs_g} goal={carbsGoal} color={MACRO_COLORS.carbs} />
        <MacroBar label="Fiber" eaten={weekTotals.fiber_g} goal={fiberGoal} color={MACRO_COLORS.fiber} />
      </div>
    </div>
  );
}
