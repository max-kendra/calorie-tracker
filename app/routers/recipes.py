import logging
from decimal import Decimal
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy.orm import Session, joinedload

from app.auth import require_api_key
from app.database import get_db
from app.models import Item, Recipe, RecipeIngredient
from app.nutrition import compute_recipe_totals, to_display
from app.search import multi_column_search_filter
from app.schemas import (
    RecipeCreate,
    RecipeIngredientCreate,
    RecipeOut,
    RecipeType,
    RecipeUpdate,
)

router = APIRouter(
    prefix="/recipes",
    tags=["recipes"],
    dependencies=[Depends(require_api_key)],
)

logger = logging.getLogger(__name__)


def _build_recipe_out(recipe: Recipe) -> RecipeOut:
    totals = compute_recipe_totals(recipe)  # RawTotals, precise
    servings = recipe.servings or Decimal("1")
    per_serving = totals / servings  # still RawTotals, precise

    return RecipeOut(
        recipe_id=recipe.recipe_id,
        name=recipe.name,
        recipe_type=recipe.recipe_type,
        instructions=recipe.instructions,
        image_path=recipe.image_path,
        servings=recipe.servings,
        created_at=recipe.created_at,
        updated_at=recipe.updated_at,
        ingredients=[
            {"item_id": ri.item_id, "quantity_g": ri.quantity_g, "item_name": ri.item.name}
            for ri in recipe.ingredients
        ],
        totals=to_display(totals),
        totals_per_serving=to_display(per_serving),
    )


def _get_recipe_or_404(recipe_id: int, db: Session) -> Recipe:
    recipe = (
        db.query(Recipe)
        .options(joinedload(Recipe.ingredients).joinedload(RecipeIngredient.item))
        .filter(Recipe.recipe_id == recipe_id)
        .first()
    )
    if not recipe:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Recipe not found")
    return recipe


def _validate_items_exist(item_ids: list[int], db: Session):
    found = db.query(Item.item_id).filter(Item.item_id.in_(item_ids)).all()
    found_ids = {row[0] for row in found}
    missing = set(item_ids) - found_ids
    if missing:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Unknown item_id(s): {sorted(missing)}",
        )


@router.post("", response_model=RecipeOut, status_code=status.HTTP_201_CREATED)
def create_recipe(payload: RecipeCreate, db: Session = Depends(get_db)):
    # TEMPORARY - remove once the "bookmark saves as recipe not meal"
    # bug is diagnosed. Logs the raw payload FastAPI actually parsed
    # from the request body, so we can see exactly what recipe_type
    # (and everything else) the Android app really sent, rather than
    # inferring it from source code that may not match what's actually
    # installed/running.
    logger.info("POST /recipes payload: %s", payload.model_dump())

    if payload.ingredients:
        _validate_items_exist([i.item_id for i in payload.ingredients], db)

    recipe = Recipe(
        name=payload.name,
        recipe_type=payload.recipe_type,
        instructions=payload.instructions,
        image_path=payload.image_path,
        servings=payload.servings,
    )
    db.add(recipe)
    db.flush()  # get recipe_id before inserting ingredients

    for ing in payload.ingredients:
        db.add(RecipeIngredient(recipe_id=recipe.recipe_id, item_id=ing.item_id, quantity_g=ing.quantity_g))

    db.commit()
    recipe = _get_recipe_or_404(recipe.recipe_id, db)
    return _build_recipe_out(recipe)


@router.get("/{recipe_id}", response_model=RecipeOut)
def get_recipe(recipe_id: int, db: Session = Depends(get_db)):
    recipe = _get_recipe_or_404(recipe_id, db)
    return _build_recipe_out(recipe)


@router.get("", response_model=list[RecipeOut])
def list_recipes(
    q: Optional[str] = Query(None, description="Search by name"),
    recipe_type: Optional[RecipeType] = Query(None, description="Filter by 'recipe' or 'meal'"),
    limit: int = Query(50, le=200),
    offset: int = Query(0),
    db: Session = Depends(get_db),
):
    """Backs the My Foods Recipe/Meal tabs, filtered by recipe_type."""
    query = db.query(Recipe).options(
        joinedload(Recipe.ingredients).joinedload(RecipeIngredient.item)
    )

    if q:
        search_filter = multi_column_search_filter(q, Recipe.name)
        if search_filter is not None:
            query = query.filter(search_filter)
    if recipe_type:
        query = query.filter(Recipe.recipe_type == recipe_type)

    recipes = query.order_by(Recipe.updated_at.desc()).offset(offset).limit(limit).all()
    return [_build_recipe_out(r) for r in recipes]


@router.patch("/{recipe_id}", response_model=RecipeOut)
def update_recipe(recipe_id: int, payload: RecipeUpdate, db: Session = Depends(get_db)):
    recipe = _get_recipe_or_404(recipe_id, db)

    for field, value in payload.model_dump(exclude_unset=True).items():
        setattr(recipe, field, value)

    db.commit()
    recipe = _get_recipe_or_404(recipe_id, db)
    return _build_recipe_out(recipe)


@router.delete("/{recipe_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_recipe(recipe_id: int, db: Session = Depends(get_db)):
    recipe = _get_recipe_or_404(recipe_id, db)
    # FK constraint blocks this if any `logs`/`meal_plans` row still
    # references this recipe — deliberate, same protection as item deletes.
    db.delete(recipe)
    db.commit()
    return None


@router.post("/{recipe_id}/ingredients", response_model=RecipeOut, status_code=status.HTTP_201_CREATED)
def add_ingredient(recipe_id: int, payload: RecipeIngredientCreate, db: Session = Depends(get_db)):
    _get_recipe_or_404(recipe_id, db)  # 404 check
    _validate_items_exist([payload.item_id], db)

    existing = (
        db.query(RecipeIngredient)
        .filter(RecipeIngredient.recipe_id == recipe_id, RecipeIngredient.item_id == payload.item_id)
        .first()
    )
    if existing:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="This item is already an ingredient in this recipe — use PATCH to change its quantity",
        )

    db.add(RecipeIngredient(recipe_id=recipe_id, item_id=payload.item_id, quantity_g=payload.quantity_g))
    db.commit()
    recipe = _get_recipe_or_404(recipe_id, db)
    return _build_recipe_out(recipe)


@router.patch("/{recipe_id}/ingredients/{item_id}", response_model=RecipeOut)
def update_ingredient_quantity(
    recipe_id: int, item_id: int, quantity_g: Decimal, db: Session = Depends(get_db)
):
    ri = (
        db.query(RecipeIngredient)
        .filter(RecipeIngredient.recipe_id == recipe_id, RecipeIngredient.item_id == item_id)
        .first()
    )
    if not ri:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Ingredient not in this recipe")

    ri.quantity_g = quantity_g
    db.commit()
    recipe = _get_recipe_or_404(recipe_id, db)
    return _build_recipe_out(recipe)


@router.delete("/{recipe_id}/ingredients/{item_id}", response_model=RecipeOut)
def remove_ingredient(recipe_id: int, item_id: int, db: Session = Depends(get_db)):
    ri = (
        db.query(RecipeIngredient)
        .filter(RecipeIngredient.recipe_id == recipe_id, RecipeIngredient.item_id == item_id)
        .first()
    )
    if not ri:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Ingredient not in this recipe")

    db.delete(ri)
    db.commit()
    recipe = _get_recipe_or_404(recipe_id, db)
    return _build_recipe_out(recipe)