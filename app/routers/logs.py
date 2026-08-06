from datetime import date as date_type
from decimal import Decimal
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import desc, func
from sqlalchemy.orm import Session, joinedload, selectinload

from app.auth import require_api_key
from app.database import get_db
from app.models import Item, Log, LoggedRecipeIngredient, Recipe, ServingSize
from app.nutrition import (
    ceil_int,
    compute_item_totals,
    compute_recipe_ingredient_snapshot,
    compute_recipe_totals_for_quantity,
    RawTotals,
)
from app.schemas import (
    DailySummary,
    ExtendedNutritionTotals,
    ItemOut,
    LogCreate,
    LoggedRecipeIngredientOut,
    LogFromMealRequest,
    LogOut,
    LogUpdate,
    MealType,
    NutritionTotals,
)

router = APIRouter(
    prefix="/logs",
    tags=["logs"],
    dependencies=[Depends(require_api_key)],
)


def _validate_and_compute(payload, db: Session) -> tuple[
    RawTotals, Optional[str], Optional[str], Optional[str], Optional[str], Optional[Decimal],
    Optional[list[tuple["RecipeIngredient", RawTotals, Decimal]]], Optional[Decimal],
]:
    """
    Shared validation + macro computation for both logs and meal_plans.
    Returns (totals, item_name, recipe_name, image_path, serving_name,
    serving_weight_g, ingredient_snapshot, recipe_servings) for
    convenience/display - image_path/serving_name/serving_weight_g
    denormalized onto LogOut so the client can show a thumbnail and the
    actual unit logged (e.g. "2 slices (75g)") without a separate lookup
    per row. Without serving_name, the client only has serving_size_id
    and has no way to resolve what unit that actually was, which is why
    the log list was always showing quantity in grams even when a named
    serving was used; serving_weight_g on top of that is what lets it
    also show the gram equivalent alongside the serving name, rather
    than just the serving name on its own.

    ingredient_snapshot is only non-None for recipe_id logs - see
    compute_recipe_ingredient_snapshot's own docstring for why this needs
    to be captured once at create_log time rather than resolved live.

    recipe_servings is the recipe's total servings YIELD at this exact
    moment (only non-None for recipe_id logs) - frozen onto
    Log.recipe_servings_logged the same way, for the same reason: it's
    the denominator needed to make sense of "how many servings were
    consumed" once the recipe's own servings count has since been edited
    (see that column's own doc comment on the Log model).
    """
    if (payload.item_id is None) == (payload.recipe_id is None):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Exactly one of item_id or recipe_id must be set",
        )

    if payload.item_id is not None:
        item = db.query(Item).filter(Item.item_id == payload.item_id).first()
        if not item:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Unknown item_id")

        serving = None
        if payload.serving_size_id is not None:
            serving = (
                db.query(ServingSize)
                .filter(ServingSize.id == payload.serving_size_id, ServingSize.item_id == item.item_id)
                .first()
            )
            if not serving:
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST,
                    detail="serving_size_id does not belong to the given item_id",
                )

        totals = compute_item_totals(item, payload.quantity, serving)
        return (
            totals, item.name, None, item.image_path,
            serving.name if serving else None, serving.weight_g if serving else None, None, None,
        )

    else:
        recipe = (
            db.query(Recipe)
            .options(joinedload(Recipe.ingredients))
            .filter(Recipe.recipe_id == payload.recipe_id)
            .first()
        )
        if not recipe:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Unknown recipe_id")

        totals = compute_recipe_totals_for_quantity(recipe, payload.quantity)
        ingredient_snapshot = compute_recipe_ingredient_snapshot(recipe, payload.quantity)
        return totals, None, recipe.name, recipe.image_path, None, None, ingredient_snapshot, recipe.servings


def _log_to_out(
    log: Log,
    item_name: Optional[str],
    recipe_name: Optional[str],
    image_path: Optional[str] = None,
    serving_name: Optional[str] = None,
    serving_weight_g: Optional[Decimal] = None,
) -> LogOut:
    """The DB stores kcal_logged etc as precise Decimal (frozen at write
    time). Rounded UP to int here, at the display boundary only.

    item_name/recipe_name/image_path here are FALLBACK values (typically
    a live lookup done by the caller) - log.item_name_logged/
    recipe_name_logged/image_path_logged, frozen at create_log time, are
    always preferred when present. The only rows where the frozen column
    is NULL are ones that existed before this feature shipped (see that
    migration's backfill note) - every row created since always has it,
    so the fallback exists purely for that one-time transition and
    should never diverge from it going forward.
    """
    resolved_item_name = log.item_name_logged or item_name
    resolved_recipe_name = log.recipe_name_logged or recipe_name
    resolved_image_path = log.image_path_logged or image_path
    ingredients = [
        LoggedRecipeIngredientOut(
            item_id=ing.item_id,
            item_name=ing.item_name_logged,
            serving_size_id=ing.serving_size_id,
            serving_size_name=ing.serving_size_name_logged,
            serving_size_weight_g=ing.serving_size_weight_g_logged,
            quantity=ing.quantity,
            grams=ing.grams_logged,
            kcal=ceil_int(ing.kcal_logged),
            protein_g=ceil_int(ing.protein_g_logged) if ing.protein_g_logged is not None else None,
            carbs_g=ceil_int(ing.carbs_g_logged) if ing.carbs_g_logged is not None else None,
            fat_g=ceil_int(ing.fat_g_logged) if ing.fat_g_logged is not None else None,
            fiber_g=ceil_int(ing.fiber_g_logged) if ing.fiber_g_logged is not None else None,
            sugar_g=ceil_int(ing.sugar_g_logged) if ing.sugar_g_logged is not None else None,
            countable_sugar_g=(
                ceil_int(ing.countable_sugar_g_logged) if ing.countable_sugar_g_logged is not None else None
            ),
            saturated_fat_g=(
                ceil_int(ing.saturated_fat_g_logged) if ing.saturated_fat_g_logged is not None else None
            ),
            sodium_mg=ceil_int(ing.sodium_mg_logged) if ing.sodium_mg_logged is not None else None,
        )
        for ing in log.ingredients
    ]
    return LogOut(
        id=log.id,
        date=log.date,
        meal_type=log.meal_type,
        item_id=log.item_id,
        recipe_id=log.recipe_id,
        serving_size_id=log.serving_size_id,
        serving_size_name=serving_name,
        serving_size_weight_g=serving_weight_g,
        quantity=log.quantity,
        ingredients=ingredients,
        has_ingredient_snapshot=log.has_ingredient_snapshot,
        recipe_servings_logged=log.recipe_servings_logged,
        logged_at=log.logged_at,
        kcal_logged=ceil_int(log.kcal_logged),
        protein_g_logged=ceil_int(log.protein_g_logged),
        carbs_g_logged=ceil_int(log.carbs_g_logged),
        fat_g_logged=ceil_int(log.fat_g_logged),
        fiber_g_logged=ceil_int(log.fiber_g_logged),
        sugar_g_logged=ceil_int(log.sugar_g_logged),
        countable_sugar_g_logged=ceil_int(log.countable_sugar_g_logged),
        saturated_fat_g_logged=ceil_int(log.saturated_fat_g_logged),
        sodium_mg_logged=ceil_int(log.sodium_mg_logged),
        item_name=resolved_item_name,
        recipe_name=resolved_recipe_name,
        image_path=resolved_image_path,
    )


@router.post("", response_model=LogOut, status_code=status.HTTP_201_CREATED)
def create_log(payload: LogCreate, db: Session = Depends(get_db)):
    """
    Computes and SNAPSHOTS macros at write time. This is deliberate: if the
    source item/recipe is edited later, this log's numbers must not change
    - historical days/weekly summaries reflect what was actually counted
    at the time (see design doc). item_name_logged/recipe_name_logged/
    image_path_logged and (for recipes) the per-ingredient
    LoggedRecipeIngredient rows freeze the same way, for the same reason -
    see Log's and LoggedRecipeIngredient's own docstrings.
    """
    totals, item_name, recipe_name, image_path, serving_name, serving_weight_g, ingredient_snapshot, recipe_servings = (
        _validate_and_compute(payload, db)
    )

    log = Log(
        date=payload.date,
        meal_type=payload.meal_type,
        item_id=payload.item_id,
        recipe_id=payload.recipe_id,
        serving_size_id=payload.serving_size_id,
        quantity=payload.quantity,
        item_name_logged=item_name,
        recipe_name_logged=recipe_name,
        image_path_logged=image_path,
        recipe_servings_logged=recipe_servings,
        has_ingredient_snapshot=True,
        kcal_logged=totals.kcal,
        protein_g_logged=totals.protein_g,
        carbs_g_logged=totals.carbs_g,
        fat_g_logged=totals.fat_g,
        fiber_g_logged=totals.fiber_g,
        sugar_g_logged=totals.sugar_g,
        countable_sugar_g_logged=totals.countable_sugar_g,
        saturated_fat_g_logged=totals.saturated_fat_g,
        sodium_mg_logged=totals.sodium_mg,
    )
    if ingredient_snapshot is not None:
        log.ingredients = [
            LoggedRecipeIngredient(
                item_id=ri.item_id,
                item_name_logged=ri.item.name,
                serving_size_id=ri.serving_size_id,
                serving_size_name_logged=ri.serving_size.name if ri.serving_size else None,
                serving_size_weight_g_logged=ri.serving_size.weight_g if ri.serving_size else None,
                quantity=ri.quantity,
                grams_logged=grams,
                kcal_logged=ing_totals.kcal,
                protein_g_logged=ing_totals.protein_g,
                carbs_g_logged=ing_totals.carbs_g,
                fat_g_logged=ing_totals.fat_g,
                fiber_g_logged=ing_totals.fiber_g,
                sugar_g_logged=ing_totals.sugar_g,
                countable_sugar_g_logged=ing_totals.countable_sugar_g,
                saturated_fat_g_logged=ing_totals.saturated_fat_g,
                sodium_mg_logged=ing_totals.sodium_mg,
            )
            for ri, ing_totals, grams in ingredient_snapshot
        ]
    db.add(log)
    if payload.item_id is not None:
        # Backs "recently logged" ordering in the search/recent item
        # list (see items.py's list_items) - this is the actual moment
        # an item gets logged, as opposed to just edited. Also
        # remembers the quantity/serving actually used (see
        # ItemOut.last_logged_quantity's doc comment) so the picker can
        # default to it next time, across ANY meal/day/session.
        db.query(Item).filter(Item.item_id == payload.item_id).update({
            "last_logged_at": func.now(),
            "last_logged_quantity": payload.quantity,
            "last_logged_serving_size_id": payload.serving_size_id,
        })
    elif payload.recipe_id is not None:
        # Same reasoning, for the unfiltered recipe/meal list (see
        # recipes.py's list_recipes).
        db.query(Recipe).filter(Recipe.recipe_id == payload.recipe_id).update({"last_logged_at": func.now()})
    db.commit()
    db.refresh(log)
    return _log_to_out(log, item_name, recipe_name, image_path, serving_name, serving_weight_g)


@router.post("/from-meal", response_model=list[LogOut], status_code=status.HTTP_201_CREATED)
def create_logs_from_meal(payload: LogFromMealRequest, db: Session = Depends(get_db)):
    """
    Expands a saved "meal" into one log PER INGREDIENT, rather than a
    single log referencing recipe_id the way an actual recipe logs (see
    POST /logs with recipe_id set, still used for real recipes). This is
    the functional distinction between the two: a recipe stays one
    atomic log entry; a meal's ingredients land individually, each fully
    editable/removable afterward - same as if the user had added each
    item to this meal one at a time (see design doc).

    Only valid for recipe_type="meal" - rejects actual recipes, which
    should keep logging the normal atomic way.
    """
    recipe = (
        db.query(Recipe)
        .options(joinedload(Recipe.ingredients))
        .filter(Recipe.recipe_id == payload.recipe_id)
        .first()
    )
    if not recipe:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Meal not found")
    if recipe.recipe_type != "meal":
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="This is a recipe, not a meal - log it with POST /logs and recipe_id instead",
        )
    if not recipe.ingredients:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="This meal has no ingredients")

    # Look up all the ingredient items in one query rather than one
    # query per ingredient in the loop below.
    item_ids = [ing.item_id for ing in recipe.ingredients]
    items_by_id = {item.item_id: item for item in db.query(Item).filter(Item.item_id.in_(item_ids)).all()}

    created: list[tuple[Log, str, Optional[str]]] = []
    for ingredient in recipe.ingredients:
        item = items_by_id.get(ingredient.item_id)
        if not item:
            # The ingredient's item was deleted since this meal was
            # saved - skip it rather than fail the whole request over
            # one missing item.
            continue

        totals = compute_item_totals(item, ingredient.quantity, ingredient.serving_size)
        log = Log(
            date=payload.date,
            meal_type=payload.meal_type,
            item_id=item.item_id,
            recipe_id=None,
            serving_size_id=ingredient.serving_size_id,
            quantity=ingredient.quantity,
            item_name_logged=item.name,
            image_path_logged=item.image_path,
            has_ingredient_snapshot=True,
            kcal_logged=totals.kcal,
            protein_g_logged=totals.protein_g,
            carbs_g_logged=totals.carbs_g,
            fat_g_logged=totals.fat_g,
            fiber_g_logged=totals.fiber_g,
            sugar_g_logged=totals.sugar_g,
            countable_sugar_g_logged=totals.countable_sugar_g,
            saturated_fat_g_logged=totals.saturated_fat_g,
            sodium_mg_logged=totals.sodium_mg,
        )
        db.add(log)
        # Same "recently logged" bump as create_log - this is a
        # different code path (doesn't go through that function), so
        # needs its own update rather than relying on that one's.
        item.last_logged_at = func.now()
        item.last_logged_quantity = ingredient.quantity
        item.last_logged_serving_size_id = ingredient.serving_size_id
        created.append((log, item.name, item.image_path))

    if not created:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="None of this meal's ingredient items exist anymore",
        )

    # Same "recently logged" bump as the recipe branch in create_log -
    # this expands into per-ingredient item logs instead, but the meal
    # itself was still just logged and should sort accordingly too.
    recipe.last_logged_at = func.now()

    db.commit()
    result = []
    for log, item_name, image_path in created:
        db.refresh(log)
        result.append(_log_to_out(log, item_name, None, image_path))
    return result


@router.get("", response_model=list[LogOut])
def list_logs(
    date: Optional[date_type] = Query(None, description="Single day (Journal view)"),
    start_date: Optional[date_type] = Query(None),
    end_date: Optional[date_type] = Query(None),
    meal_type: Optional[MealType] = Query(None),
    db: Session = Depends(get_db),
):
    """Backs the Journal screen - a single `date` gives one day's log,
    start/end gives a range for weekly summaries."""
    query = db.query(Log).options(selectinload(Log.ingredients))

    if date:
        query = query.filter(Log.date == date)
    else:
        if start_date:
            query = query.filter(Log.date >= start_date)
        if end_date:
            query = query.filter(Log.date <= end_date)

    if meal_type:
        query = query.filter(Log.meal_type == meal_type)

    logs = query.order_by(Log.date, Log.logged_at).all()

    item_ids = {l.item_id for l in logs if l.item_id}
    recipe_ids = {l.recipe_id for l in logs if l.recipe_id}
    serving_ids = {l.serving_size_id for l in logs if l.serving_size_id}
    item_names = {
        i.item_id: i.name for i in db.query(Item).filter(Item.item_id.in_(item_ids)).all()
    } if item_ids else {}
    recipe_names = {
        r.recipe_id: r.name for r in db.query(Recipe).filter(Recipe.recipe_id.in_(recipe_ids)).all()
    } if recipe_ids else {}
    item_images = {
        i.item_id: i.image_path for i in db.query(Item).filter(Item.item_id.in_(item_ids)).all()
    } if item_ids else {}
    recipe_images = {
        r.recipe_id: r.image_path for r in db.query(Recipe).filter(Recipe.recipe_id.in_(recipe_ids)).all()
    } if recipe_ids else {}
    serving_names = {
        s.id: s.name for s in db.query(ServingSize).filter(ServingSize.id.in_(serving_ids)).all()
    } if serving_ids else {}
    serving_weights = {
        s.id: s.weight_g for s in db.query(ServingSize).filter(ServingSize.id.in_(serving_ids)).all()
    } if serving_ids else {}

    return [
        _log_to_out(
            l,
            item_names.get(l.item_id),
            recipe_names.get(l.recipe_id),
            item_images.get(l.item_id) or recipe_images.get(l.recipe_id),
            serving_names.get(l.serving_size_id),
            serving_weights.get(l.serving_size_id)
        ) for l in logs
    ]


@router.get("/recent-items", response_model=list[ItemOut])
def recent_items(
    meal_type: Optional[MealType] = Query(None, description="Narrow to items logged for this meal type"),
    limit: int = Query(20, le=50),
    db: Session = Depends(get_db),
):
    """
    Items sorted by most recently LOGGED (not most recently added to the
    catalog) - backs the Add Item sheet's default "Saved" view, so the
    things you actually eat regularly float to the top rather than
    whatever you happened to create first.

    Recipe-based logs are excluded here - this returns Items only, not
    Recipes - kept simple for the first version of this endpoint;
    revisit if recipes need to show up in "recently logged" too.
    """
    last_logged_subq = (
        db.query(Log.item_id, func.max(Log.logged_at).label("last_logged"))
        .filter(Log.item_id.isnot(None))
    )
    if meal_type:
        last_logged_subq = last_logged_subq.filter(Log.meal_type == meal_type)
    last_logged_subq = (
        last_logged_subq.group_by(Log.item_id)
        .order_by(desc("last_logged"))
        .limit(limit)
        .subquery()
    )

    items = (
        db.query(Item)
        .join(last_logged_subq, Item.item_id == last_logged_subq.c.item_id)
        .order_by(desc(last_logged_subq.c.last_logged))
        .all()
    )
    return items


@router.get("/summary/daily", response_model=list[DailySummary])
def daily_summary(
    start_date: date_type = Query(...),
    end_date: date_type = Query(...),
    db: Session = Depends(get_db),
):
    """
    Weekly/daily summary view - sums the FROZEN kcal_logged/etc columns
    grouped by date, not a live recomputation. This is what makes past
    summaries stable even if items/recipes are edited afterward.
    """
    logs = (
        db.query(Log)
        .filter(Log.date >= start_date, Log.date <= end_date)
        .order_by(Log.date)
        .all()
    )

    zero = Decimal("0")
    by_date: dict[date_type, dict[str, Decimal]] = {}
    for l in logs:
        d = by_date.setdefault(
            l.date,
            {
                "kcal": zero,
                "protein_g": zero,
                "carbs_g": zero,
                "fat_g": zero,
                "fiber_g": zero,
                "sugar_g": zero,
                "countable_sugar_g": zero,
                "saturated_fat_g": zero,
                "sodium_mg": zero,
            },
        )
        d["kcal"] += l.kcal_logged
        d["protein_g"] += l.protein_g_logged
        d["carbs_g"] += l.carbs_g_logged
        d["fiber_g"] += l.fiber_g_logged
        d["sugar_g"] += l.sugar_g_logged
        d["countable_sugar_g"] += l.countable_sugar_g_logged
        d["fat_g"] += l.fat_g_logged
        d["saturated_fat_g"] += l.saturated_fat_g_logged
        d["sodium_mg"] += l.sodium_mg_logged

    return [
        DailySummary(
            date=d,
            totals=ExtendedNutritionTotals(
                kcal=ceil_int(totals["kcal"]),
                protein_g=ceil_int(totals["protein_g"]),
                carbs_g=ceil_int(totals["carbs_g"]),
                fat_g=ceil_int(totals["fat_g"]),
                fiber_g=ceil_int(totals["fiber_g"]),
                sugar_g=ceil_int(totals["sugar_g"]),
                countable_sugar_g=ceil_int(totals["countable_sugar_g"]),
                saturated_fat_g=ceil_int(totals["saturated_fat_g"]),
                sodium_mg=ceil_int(totals["sodium_mg"]),
            ),
        )
        for d, totals in sorted(by_date.items())
    ]


@router.patch("/{log_id}", response_model=LogOut)
def update_log(log_id: int, payload: LogUpdate, db: Session = Depends(get_db)):
    """
    Quantity-only edit - item_id/recipe_id/date/meal_type stay fixed
    (see LogUpdate's doc comment).

    Item logs: re-runs the same snapshot computation create_log does, so
    the log's macros stay internally consistent with its new quantity.
    This IS a live lookup against the item's current macros, same as
    it's always been - editing an item's own nutrition data is a
    separate concern from this endpoint.

    Recipe logs WITH a frozen ingredient snapshot (i.e. created after
    this feature shipped): deliberately does NOT re-run
    _validate_and_compute, which would recompute against the recipe's
    CURRENT ingredients - only quantity is editable for a recipe log
    (serving_size_id is item-only), so any change is just a proportional
    scale of the already-frozen totals and ingredient rows. Recomputing
    live here would silently substitute today's recipe composition for
    whatever was actually logged, defeating the whole point of freezing
    it in the first place (see LoggedRecipeIngredient's docstring).

    Recipe logs with NO snapshot (pre-existing logs from before this
    feature) have no frozen composition to scale, so they fall back to
    the old live-recompute behavior - a known limitation for that
    pre-existing data only (see the migration's own note on why backfill
    isn't possible here).
    """
    log = db.query(Log).filter(Log.id == log_id).first()
    if not log:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Log not found")

    provided = payload.model_fields_set
    if "quantity" not in provided and "serving_size_id" not in provided:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Provide at least one of quantity or serving_size_id",
        )

    merged = LogCreate(
        date=log.date,
        meal_type=log.meal_type,
        item_id=log.item_id,
        recipe_id=log.recipe_id,
        serving_size_id=payload.serving_size_id if "serving_size_id" in provided else log.serving_size_id,
        quantity=payload.quantity if "quantity" in provided else log.quantity,
    )

    if log.recipe_id is not None and log.ingredients:
        factor = merged.quantity / log.quantity
        totals = RawTotals(
            kcal=log.kcal_logged * factor,
            protein_g=log.protein_g_logged * factor,
            carbs_g=log.carbs_g_logged * factor,
            fat_g=log.fat_g_logged * factor,
            fiber_g=log.fiber_g_logged * factor,
            sugar_g=log.sugar_g_logged * factor,
            countable_sugar_g=log.countable_sugar_g_logged * factor,
            saturated_fat_g=log.saturated_fat_g_logged * factor,
            sodium_mg=log.sodium_mg_logged * factor,
        )
        for ing in log.ingredients:
            ing.quantity = ing.quantity * factor
            ing.grams_logged = ing.grams_logged * factor
            ing.kcal_logged = ing.kcal_logged * factor
            # Same proportional rescale for the widened macro fields -
            # nullable (rows from before they existed), so only scale
            # what's actually there rather than turning a legitimate
            # "no historical data" NULL into a wrong 0.
            if ing.protein_g_logged is not None:
                ing.protein_g_logged = ing.protein_g_logged * factor
            if ing.carbs_g_logged is not None:
                ing.carbs_g_logged = ing.carbs_g_logged * factor
            if ing.fat_g_logged is not None:
                ing.fat_g_logged = ing.fat_g_logged * factor
            if ing.fiber_g_logged is not None:
                ing.fiber_g_logged = ing.fiber_g_logged * factor
            if ing.sugar_g_logged is not None:
                ing.sugar_g_logged = ing.sugar_g_logged * factor
            if ing.countable_sugar_g_logged is not None:
                ing.countable_sugar_g_logged = ing.countable_sugar_g_logged * factor
            if ing.saturated_fat_g_logged is not None:
                ing.saturated_fat_g_logged = ing.saturated_fat_g_logged * factor
            if ing.sodium_mg_logged is not None:
                ing.sodium_mg_logged = ing.sodium_mg_logged * factor
        item_name, recipe_name, image_path = log.item_name_logged, log.recipe_name_logged, log.image_path_logged
        serving_name, serving_weight_g = None, None
    else:
        totals, item_name, recipe_name, image_path, serving_name, serving_weight_g, _, recipe_servings = (
            _validate_and_compute(merged, db)
        )
        # Opportunistic: a legacy recipe log with no ingredient snapshot
        # (the branch above is for logs that DO have one) had no
        # recipe_servings_logged either - fill it in now from whatever
        # the recipe's servings count is at this edit, same
        # "recompute against the live recipe" fallback this whole
        # branch already uses for everything else.
        if log.recipe_id is not None:
            log.recipe_servings_logged = recipe_servings

    log.serving_size_id = merged.serving_size_id
    log.quantity = merged.quantity
    log.kcal_logged = totals.kcal
    log.protein_g_logged = totals.protein_g
    log.carbs_g_logged = totals.carbs_g
    log.fat_g_logged = totals.fat_g
    log.fiber_g_logged = totals.fiber_g
    log.sugar_g_logged = totals.sugar_g
    log.countable_sugar_g_logged = totals.countable_sugar_g
    log.saturated_fat_g_logged = totals.saturated_fat_g
    log.sodium_mg_logged = totals.sodium_mg
    if log.item_id is not None:
        # Same reasoning as create_log's bump - adjusting an existing
        # log's quantity is just as valid a signal of "this is the
        # amount actually used" as logging fresh.
        db.query(Item).filter(Item.item_id == log.item_id).update({
            "last_logged_at": func.now(),
            "last_logged_quantity": log.quantity,
            "last_logged_serving_size_id": log.serving_size_id,
        })
    elif log.recipe_id is not None:
        # Same reasoning, for the recipe/meal search list's own
        # last_logged_at ordering (see recipes.py's list_recipes) - this
        # was previously missing entirely, so editing a logged recipe's
        # quantity never bumped the recipe's own recency.
        db.query(Recipe).filter(Recipe.recipe_id == log.recipe_id).update({"last_logged_at": func.now()})
    db.commit()
    db.refresh(log)
    return _log_to_out(log, item_name, recipe_name, image_path, serving_name, serving_weight_g)


@router.delete("/{log_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_log(log_id: int, db: Session = Depends(get_db)):
    log = db.query(Log).filter(Log.id == log_id).first()
    if not log:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Log not found")
    db.delete(log)
    db.commit()
    return None


@router.get("/{log_id}", response_model=LogOut)
def get_log(log_id: int, db: Session = Depends(get_db)):
    # Registered LAST among the GET routes - this is a catch-all path
    # param, and FastAPI matches routes in registration order, so it
    # must come after every fixed-path GET (/recent-items,
    # /summary/daily, and the bare "" list route) or it'll shadow them,
    # trying to parse e.g. "recent-items" as an int and 422ing instead
    # of ever reaching those handlers.
    log = db.query(Log).options(selectinload(Log.ingredients)).filter(Log.id == log_id).first()
    if not log:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Log not found")

    item_name = log.item_id and db.query(Item.name).filter(Item.item_id == log.item_id).scalar()
    recipe_name = log.recipe_id and db.query(Recipe.name).filter(Recipe.recipe_id == log.recipe_id).scalar()
    image_path = (
        log.item_id and db.query(Item.image_path).filter(Item.item_id == log.item_id).scalar()
        or log.recipe_id and db.query(Recipe.image_path).filter(Recipe.recipe_id == log.recipe_id).scalar()
    )
    serving_name = (
        log.serving_size_id
        and db.query(ServingSize.name).filter(ServingSize.id == log.serving_size_id).scalar()
    )
    serving_weight_g = (
        log.serving_size_id
        and db.query(ServingSize.weight_g).filter(ServingSize.id == log.serving_size_id).scalar()
    )
    return _log_to_out(log, item_name, recipe_name, image_path, serving_name, serving_weight_g)