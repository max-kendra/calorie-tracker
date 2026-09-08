import { darkenColor } from "@/lib/colors";

interface MacroBarProps {
  label: string;
  eaten: number;
  goal: number;
  color: string;
  unit?: string;
  /** Compact = thin bar, small text, no "+X over" line - used at the
   * meal-header/day-header level where space is tight. Full = the
   * larger week-summary treatment. */
  compact?: boolean;
}

/** Same treatment as the Android app's WeeklyMacroBar (see that
 * composable's own doc comment): a base fill to `eaten/goal`, plus a
 * SECOND, darker-shaded segment stacked on top (not appended after)
 * showing how far over goal you are, capped at a second full bar's
 * width. Two Boxes/divs at the same position, not a single bar
 * extending past 100%, since the container has no room for that. */
export function MacroBar({ label, eaten, goal, color, unit = "g", compact = false }: MacroBarProps) {
  const fraction = goal > 0 ? Math.min(eaten / goal, 1) : 0;
  const over = eaten - goal;
  const overflowFraction = goal > 0 && over > 0 ? Math.min(over / goal, 1) : 0;
  const barHeight = compact ? "h-1.5" : "h-2.5";

  return (
    <div className="w-full">
      <div className={`flex items-baseline justify-between ${compact ? "text-xs" : "text-sm"}`}>
        <span className="font-medium text-gray-700">{label}</span>
        <span className="text-gray-500">
          {eaten}
          {unit} / {goal}
          {unit}
        </span>
      </div>
      <div
        className={`relative w-full ${barHeight} rounded-full mt-1 overflow-hidden`}
        style={{ backgroundColor: `${color}38` /* ~22% alpha, matches Android's copy(alpha=0.22f) */ }}
      >
        <div
          className="absolute inset-y-0 left-0 rounded-full"
          style={{ width: `${fraction * 100}%`, backgroundColor: color }}
        />
        {overflowFraction > 0 && (
          <div
            className="absolute inset-y-0 left-0 rounded-full"
            style={{ width: `${overflowFraction * 100}%`, backgroundColor: darkenColor(color) }}
          />
        )}
      </div>
      {!compact && over > 0 && (
        <div className="text-xs mt-0.5" style={{ color }}>
          +{over}
          {unit} over
        </div>
      )}
    </div>
  );
}
