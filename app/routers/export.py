from datetime import date as date_type, datetime, timedelta
from decimal import Decimal
from zoneinfo import ZoneInfo

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy.orm import Session

from app.auth import require_api_key
from app.database import get_db
from app.models import Goal, Item, Log, Recipe, ServingSize, UserProfile, WeightHistoryEntry
from app.nutrition import ceil_int

router = APIRouter(
    prefix="/export",
    tags=["export"],
    dependencies=[Depends(require_api_key)],
)

MAX_DAYS = 93
MEALS = ("breakfast", "lunch", "dinner", "snack")
MACROS = (
    ("kcal", "kcal_logged"),
    ("protein_g", "protein_g_logged"),
    ("fat_g", "fat_g_logged"),
    ("carbs_g", "carbs_g_logged"),
    ("fiber_g", "fiber_g_logged"),
)


def _num(value) -> float | int:
    d = Decimal(value)
    return int(d) if d == d.to_integral_value() else float(round(d, 2))


def _quantity_label(log: Log, serving: ServingSize | None) -> str:
    quantity = Decimal(log.quantity)
    if log.recipe_id is not None:
        return f"{_num(quantity)} {'serving' if quantity == 1 else 'servings'}"
    if serving is not None:
        grams = ceil_int(quantity * Decimal(serving.weight_g))
        return f"{_num(quantity)} {serving.name} ({grams}g)"
    return f"{ceil_int(quantity)}g"


def _totals(logs: list[Log]) -> dict:
    return {key: ceil_int(sum((Decimal(getattr(l, col)) for l in logs), Decimal(0))) for key, col in MACROS}


@router.get("")
def export_data(
    from_date: date_type = Query(alias="from"),
    to_date: date_type = Query(alias="to"),
    db: Session = Depends(get_db),
):
    """A compact, read-only snapshot of a date range for pasting into a
    conversation: day-by-day logs with precomputed totals, goals in
    effect, weight readings, and the profile numbers needed to estimate
    BMR/TDEE. Not meant for re-import (backups cover that)."""
    if to_date < from_date:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="'to' must not be before 'from'")
    day_count = (to_date - from_date).days + 1
    if day_count > MAX_DAYS:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail=f"Range too long (max {MAX_DAYS} days)"
        )

    logs = (
        db.query(Log)
        .filter(Log.date >= from_date, Log.date <= to_date)
        .order_by(Log.date, Log.logged_at, Log.id)
        .all()
    )

    serving_ids = {l.serving_size_id for l in logs if l.serving_size_id is not None}
    servings = {s.id: s for s in db.query(ServingSize).filter(ServingSize.id.in_(serving_ids)).all()} if serving_ids else {}
    item_ids = {l.item_id for l in logs if l.item_id is not None and not l.item_name_logged}
    recipe_ids = {l.recipe_id for l in logs if l.recipe_id is not None and not l.recipe_name_logged}
    live_items = {i.item_id: i.name for i in db.query(Item).filter(Item.item_id.in_(item_ids)).all()} if item_ids else {}
    live_recipes = (
        {r.recipe_id: r.name for r in db.query(Recipe).filter(Recipe.recipe_id.in_(recipe_ids)).all()}
        if recipe_ids
        else {}
    )

    by_date: dict[date_type, list[Log]] = {}
    for log in logs:
        by_date.setdefault(log.date, []).append(log)

    days = []
    for offset in range(day_count):
        day = from_date + timedelta(days=offset)
        day_logs = by_date.get(day, [])
        meals = {}
        for meal in MEALS:
            entries = []
            for log in (l for l in day_logs if l.meal_type == meal):
                name = (
                    (log.recipe_name_logged or live_recipes.get(log.recipe_id))
                    if log.recipe_id is not None
                    else (log.item_name_logged or live_items.get(log.item_id))
                )
                entries.append(
                    {
                        "name": name or "Unknown",
                        "qty": _quantity_label(log, servings.get(log.serving_size_id)),
                        **{key: ceil_int(Decimal(getattr(log, col))) for key, col in MACROS},
                    }
                )
            if entries:
                meals[meal] = entries
        days.append(
            {
                "date": day.isoformat(),
                "weekday": day.strftime("%a"),
                "logged": bool(day_logs),
                "totals": _totals(day_logs),
                "meals": meals,
            }
        )

    logged_days = [d for d in days if d["logged"]]
    summary = {
        "days_in_range": day_count,
        "days_logged": len(logged_days),
        "average_per_logged_day": (
            {key: round(sum(d["totals"][key] for d in logged_days) / len(logged_days)) for key, _ in MACROS}
            if logged_days
            else None
        ),
    }

    goal_rows = (
        db.query(Goal)
        .filter(Goal.start_date <= to_date, (Goal.end_date.is_(None)) | (Goal.end_date >= from_date))
        .order_by(Goal.start_date)
        .all()
    )
    goals = [
        {
            "from": max(g.start_date, from_date).isoformat(),
            "to": min(g.end_date or to_date, to_date).isoformat(),
            "kcal": ceil_int(Decimal(g.kcal_target)),
            "protein_g": ceil_int(Decimal(g.protein_g_target)),
            "fat_g": ceil_int(Decimal(g.fat_g_target)),
            "carbs_g": ceil_int(Decimal(g.carbs_g_target)),
            "fiber_g": ceil_int(Decimal(g.fiber_g_target)),
        }
        for g in goal_rows
    ]

    profile = db.query(UserProfile).first()
    tz = ZoneInfo(profile.timezone) if profile and profile.timezone else ZoneInfo("UTC")

    start_dt = datetime.combine(from_date - timedelta(days=1), datetime.min.time(), tzinfo=ZoneInfo("UTC"))
    end_dt = datetime.combine(to_date + timedelta(days=2), datetime.min.time(), tzinfo=ZoneInfo("UTC"))
    weight_rows = (
        db.query(WeightHistoryEntry)
        .filter(WeightHistoryEntry.recorded_at >= start_dt, WeightHistoryEntry.recorded_at < end_dt)
        .order_by(WeightHistoryEntry.recorded_at)
        .all()
    )
    latest_per_day: dict[date_type, Decimal] = {}
    for row in weight_rows:
        local_day = row.recorded_at.astimezone(tz).date()
        if from_date <= local_day <= to_date:
            latest_per_day[local_day] = Decimal(row.weight_kg)
    weight = [{"date": d.isoformat(), "kg": _num(w)} for d, w in sorted(latest_per_day.items())]

    profile_out = None
    if profile:
        profile_out = {
            "height_cm": profile.height_cm,
            "age": profile.age,
            "primary_hormone": profile.primary_hormone,
            "activity_level": profile.activity_level,
            "goal_type": profile.goal_type,
            "current_weight_kg": _num(profile.weight_kg) if profile.weight_kg is not None else None,
            "goal_weight_kg": _num(profile.goal_weight_kg) if profile.goal_weight_kg is not None else None,
        }

    return {
        "range": {"from": from_date.isoformat(), "to": to_date.isoformat(), "days": day_count},
        "units": {"energy": "kcal", "macros": "g", "weight": "kg", "height": "cm"},
        "profile": profile_out,
        "goals": goals,
        "summary": summary,
        "weight": weight,
        "days": days,
    }
