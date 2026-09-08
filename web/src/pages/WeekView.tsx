import { useState } from "react";
import { useActiveGoal, useLogsRange } from "@/api/hooks";
import { addDays, startOfWeek, toIsoDate, weekDates } from "@/lib/dates";
import { groupByDateAndMeal, sumTotals } from "@/lib/macros";
import { DayColumn } from "@/components/DayColumn";
import { WeekMacroSummary } from "@/components/WeekMacroSummary";

export function WeekView() {
  const [anchorDate, setAnchorDate] = useState(() => new Date());
  const dates = weekDates(anchorDate);
  const startDate = dates[0];
  const endDate = dates[6];

  const logsQuery = useLogsRange(startDate, endDate);
  const goalQuery = useActiveGoal();

  const todayIso = toIsoDate(new Date());
  // Only counts days up to and including today for the week-goal
  // comparison (see WeekMacroSummary's own doc comment) - future days
  // in the same week haven't happened yet, so they shouldn't inflate
  // the budget you're being compared against.
  const daysInWeekSoFar = dates.filter((d) => d <= todayIso).length || 1;

  function goToPreviousWeek() {
    setAnchorDate((prev) => addDays(startOfWeek(prev), -1));
  }
  function goToNextWeek() {
    setAnchorDate((prev) => addDays(startOfWeek(prev), 7));
  }
  function goToToday() {
    setAnchorDate(new Date());
  }

  if (logsQuery.isLoading || goalQuery.isLoading) {
    return <div className="p-8 text-center text-gray-400">Loading...</div>;
  }
  if (logsQuery.isError) {
    return <div className="p-8 text-center text-red-500">Couldn't load logs: {(logsQuery.error as Error).message}</div>;
  }

  const logs = logsQuery.data ?? [];
  const grouped = groupByDateAndMeal(logs, dates);
  const weekTotals = sumTotals(logs);

  return (
    <div className="max-w-[1600px] mx-auto p-4">
      <div className="flex items-center justify-between mb-4">
        <div className="flex items-center gap-2">
          <button onClick={goToPreviousWeek} className="px-3 py-1.5 rounded-lg bg-white shadow-sm hover:bg-gray-50">
            &larr;
          </button>
          <button onClick={goToToday} className="px-3 py-1.5 rounded-lg bg-white shadow-sm hover:bg-gray-50 text-sm">
            Today
          </button>
          <button onClick={goToNextWeek} className="px-3 py-1.5 rounded-lg bg-white shadow-sm hover:bg-gray-50">
            &rarr;
          </button>
        </div>
        <h1 className="text-xl font-bold text-gray-800">
          {startDate} &ndash; {endDate}
        </h1>
        <div />
      </div>

      <WeekMacroSummary weekTotals={weekTotals} goal={goalQuery.data} daysInWeekSoFar={daysInWeekSoFar} />

      <div className="flex gap-2 overflow-x-auto pb-4">
        {dates.map((date) => (
          <DayColumn key={date} date={date} logsByMeal={grouped[date]} goal={goalQuery.data} />
        ))}
      </div>
    </div>
  );
}
