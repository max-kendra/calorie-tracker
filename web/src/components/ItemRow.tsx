import type { Log } from "@/api/types";
import { logDisplayName, logQuantityLabel } from "@/lib/macros";
import { MACRO_COLORS } from "@/lib/colors";

/** One logged item's row - name, quantity/serving, kcal, and a compact
 * "#P • #F • #C • #Fi" macro shortcut, same color/format convention
 * used throughout the Android app's own log rows and ingredient rows. */
export function ItemRow({ log }: { log: Log }) {
  return (
    <div className="py-1.5 border-b border-gray-100 last:border-b-0">
      <div className="flex items-baseline justify-between gap-2">
        <span className="text-sm text-gray-800 truncate">{logDisplayName(log)}</span>
        <span className="text-sm text-gray-500 shrink-0">{log.kcal_logged} Cal</span>
      </div>
      <div className="flex items-baseline justify-between gap-2">
        <span className="text-xs text-gray-500">{logQuantityLabel(log)}</span>
        <span className="text-xs shrink-0 space-x-1">
          <span style={{ color: MACRO_COLORS.protein }}>{log.protein_g_logged}P</span>
          <span className="text-gray-300">·</span>
          <span style={{ color: MACRO_COLORS.fat }}>{log.fat_g_logged}F</span>
          <span className="text-gray-300">·</span>
          <span style={{ color: MACRO_COLORS.carbs }}>{log.carbs_g_logged}C</span>
          <span className="text-gray-300">·</span>
          <span style={{ color: MACRO_COLORS.fiber }}>{log.fiber_g_logged}Fi</span>
        </span>
      </div>
    </div>
  );
}
