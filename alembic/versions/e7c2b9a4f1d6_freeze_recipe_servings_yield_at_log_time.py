"""freeze recipe servings yield at log time

Revision ID: e7c2b9a4f1d6
Revises: c9a4d2f7b6e8
Create Date: 2026-07-29 00:00:00.000000

"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op


# revision identifiers, used by Alembic.
revision: str = 'e7c2b9a4f1d6'
down_revision: Union[str, Sequence[str], None] = 'c9a4d2f7b6e8'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    # Freezes recipe.servings (the recipe's total YIELD, e.g. "makes 4
    # servings") at the moment a recipe log is created - same discipline
    # as item_name_logged/recipe_name_logged/the ingredient snapshot
    # (see that migration). logs.quantity already freezes how many
    # servings YOU consumed (e.g. 1.5) and always did, since it's a
    # plain column - what was missing is the OTHER half of that
    # fraction: how many total servings the recipe made at the time.
    # Without this, "1.5 servings" has no fixed meaning to compare
    # against once the recipe's own servings count is edited later -
    # the nutrition totals/ingredient snapshot stay correct regardless
    # (they're already absolute amounts, computed once at creation
    # time), but there's no way to answer "out of how many total" for
    # an old log.
    #
    # Only meaningful for recipe_id logs - NULL for item-based logs,
    # same convention as recipe_name_logged etc.
    op.add_column("logs", sa.Column("recipe_servings_logged", sa.Numeric(), nullable=True))

    # Best-effort backfill for existing recipe logs: stamps in the
    # recipe's CURRENT servings count, since there's no historical value
    # to recover (same one-time-approximation caveat as
    # recipe_name_logged's own backfill - see that migration's note).
    op.execute(
        """
        UPDATE logs
        SET recipe_servings_logged = recipes.servings
        FROM recipes
        WHERE logs.recipe_id = recipes.recipe_id
        """
    )


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column("logs", "recipe_servings_logged")