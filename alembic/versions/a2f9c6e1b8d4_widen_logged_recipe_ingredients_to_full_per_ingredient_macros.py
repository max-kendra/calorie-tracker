"""widen logged_recipe_ingredients to full per-ingredient macros

Revision ID: a2f9c6e1b8d4
Revises: e7c2b9a4f1d6
Create Date: 2026-08-06 00:00:00.000000

"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op


# revision identifiers, used by Alembic.
revision: str = 'a2f9c6e1b8d4'
down_revision: Union[str, Sequence[str], None] = 'e7c2b9a4f1d6'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    # Widens the per-ingredient snapshot from just (grams, kcal) to the
    # full macro breakdown - protein/carbs/fat/fiber/sugar/countable
    # sugar/saturated fat/sodium, all "*_logged" to match the naming
    # convention everywhere else frozen data lives (Log.kcal_logged
    # etc).
    #
    # Not a new computation - compute_recipe_ingredient_snapshot in
    # app/nutrition.py already builds a full RawTotals per ingredient
    # at create_log time (needed to sum into the recipe log's own
    # aggregate *_logged columns in the first place); only kcal from
    # that already-computed RawTotals was ever being kept on the
    # per-ingredient row. This migration doesn't change what gets
    # computed, only what gets persisted from a computation that was
    # already happening (see design discussion: "this isn't an
    # extensive enough amount of information that'd break the app or
    # something" - correct, it's free, it just wasn't kept).
    #
    # Motivating case: retroactively telling how much of a historical
    # recipe log's sugar was "added sugar" requires knowing each
    # ingredient's own countable_sugar_g contribution, not just the
    # recipe-level aggregate - which the old (grams, kcal)-only
    # snapshot had no way to answer even in principle. Widening this
    # doesn't itself add that recompute feature, but makes it
    # something that could be built on top of NEW logs going forward
    # (see LoggedRecipeIngredient's own docstring on why per-log
    # history still can't be reconstructed for full macros).
    #
    # No backfill, same reasoning as every other frozen-snapshot column
    # in this app: there's no historical per-ingredient macro data to
    # recover for existing rows (only kcal_logged/grams_logged survived
    # from before), so these are simply NULL for anything logged before
    # this migration.
    op.add_column("logged_recipe_ingredients", sa.Column("protein_g_logged", sa.Numeric(), nullable=True))
    op.add_column("logged_recipe_ingredients", sa.Column("carbs_g_logged", sa.Numeric(), nullable=True))
    op.add_column("logged_recipe_ingredients", sa.Column("fat_g_logged", sa.Numeric(), nullable=True))
    op.add_column("logged_recipe_ingredients", sa.Column("fiber_g_logged", sa.Numeric(), nullable=True))
    op.add_column("logged_recipe_ingredients", sa.Column("sugar_g_logged", sa.Numeric(), nullable=True))
    op.add_column("logged_recipe_ingredients", sa.Column("countable_sugar_g_logged", sa.Numeric(), nullable=True))
    op.add_column("logged_recipe_ingredients", sa.Column("saturated_fat_g_logged", sa.Numeric(), nullable=True))
    op.add_column("logged_recipe_ingredients", sa.Column("sodium_mg_logged", sa.Numeric(), nullable=True))


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column("logged_recipe_ingredients", "sodium_mg_logged")
    op.drop_column("logged_recipe_ingredients", "saturated_fat_g_logged")
    op.drop_column("logged_recipe_ingredients", "countable_sugar_g_logged")
    op.drop_column("logged_recipe_ingredients", "sugar_g_logged")
    op.drop_column("logged_recipe_ingredients", "fiber_g_logged")
    op.drop_column("logged_recipe_ingredients", "fat_g_logged")
    op.drop_column("logged_recipe_ingredients", "carbs_g_logged")
    op.drop_column("logged_recipe_ingredients", "protein_g_logged")