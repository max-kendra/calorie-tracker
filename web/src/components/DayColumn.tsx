import type { Goal, Log, MealType } from "@/api/types";
import { MEAL_TYPES, sumTotals } from "@/lib/macros";
import { isToday, shortDateLabel, weekdayLabel } from "@/lib/dates";
import { parseDecimal } from "@/lib/format";
import { MealSection } from "./MealSection";

interface DayColumnProps {
  date: string;
  logsByMeal: Record<MealType, Log[]>;
  goal: Goal | undefined;
}

export function DayColumn({ date, logsByMeal, goal }: DayColumnProps) {
  const allLogs = MEAL_TYPES.flatMap((meal) => logsByMeal[meal]);
  const dayTotals = sumTotals(allLogs);
  const kcalGoal = goal ? Math.round(parseDecimal(goal.kcal_target)) : undefined;
  const today = isToday(date);

  return (
    <div className={`flex flex-col min-w-[220px] flex-1 rounded-xl ${today ? "ring-2 ring-blue-400" : ""}`}>
      <div className="px-3 py-2 bg-white rounded-t-xl border-b border-gray-100">
        <div className="flex items-baseline justify-between">
          <span className={`text-sm font-semibold ${today ? "text-blue-600" : "text-gray-800"}`}>
            {weekdayLabel(date)} <span className="font-normal text-gray-400">{shortDateLabel(date)}</span>
          </span>
        </div>
        <div className="text-xs text-gray-500 mt-0.5">
          {dayTotals.kcal}
          {kcalGoal != null ? ` / ${kcalGoal}` : ""} Cal · {dayTotals.protein_g}P · {dayTotals.fat_g}F ·{" "}
          {dayTotals.carbs_g}C · {dayTotals.fiber_g}Fi
        </div>
      </div>
      <div className="flex flex-col gap-2 p-2 bg-gray-50 rounded-b-xl flex-1">
        {MEAL_TYPES.map((mealType) => {
          const split = goal?.meal_splits.find((s) => s.meal_type === mealType);
          return (
            <MealSection
              key={mealType}
              mealType={mealType}
              logs={logsByMeal[mealType]}
              goalKcal={split?.computed_totals.kcal}
            />
          );
        })}
      </div>
    </div>
  );
}
