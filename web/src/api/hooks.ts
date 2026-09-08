import { useQuery } from "@tanstack/react-query";
import { api } from "./client";
import type { DailySummary, Goal, Log } from "./types";

/** All individual logged entries for the week in ONE call - each row
 * already carries date + meal_type, so per-meal/per-day/per-week
 * totals are just client-side grouping (see src/lib/macros.ts) rather
 * than needing a dedicated aggregate endpoint. */
export function useLogsRange(startDate: string, endDate: string) {
  return useQuery({
    queryKey: ["logs", startDate, endDate],
    queryFn: () => api.get<Log[]>(`/logs?start_date=${startDate}&end_date=${endDate}`),
  });
}

/** Backend still computes this (frozen-sum, same integrity as
 * everything else) - not strictly needed once you have useLogsRange
 * (you could sum client-side), but kept as a cheap sanity-check /
 * simpler path for day-total display specifically, since it's already
 * exactly the right shape (ExtendedNutritionTotals per date) without
 * any grouping logic on this end. */
export function useDailySummaries(startDate: string, endDate: string) {
  return useQuery({
    queryKey: ["daily-summary", startDate, endDate],
    queryFn: () =>
      api.get<DailySummary[]>(`/logs/summary/daily?start_date=${startDate}&end_date=${endDate}`),
  });
}

export function useActiveGoal() {
  return useQuery({
    queryKey: ["active-goal"],
    queryFn: () => api.get<Goal>("/goals/active"),
  });
}
