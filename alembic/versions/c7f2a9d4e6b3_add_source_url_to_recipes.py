"""add source_url to recipes

Revision ID: c7f2a9d4e6b3
Revises: b4e8f1a6c3d9
Create Date: 2026-07-20 00:00:00.000000

"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op


# revision identifiers, used by Alembic.
revision: str = 'c7f2a9d4e6b3'
down_revision: Union[str, Sequence[str], None] = 'b4e8f1a6c3d9'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    # Optional link to wherever a recipe/meal originally came from (a
    # blog post, a cookbook site, etc) - shown alongside instructions on
    # the new dedicated Recipes tab (see design discussion: replacing
    # Meal Plan with a recipe browser that shows "nicely formatted
    # step-by-step instructions or a source website"). Available to
    # BOTH recipe_type values, not just "recipe" - the two share this
    # same table/column, and a "meal" can be just as involved as a
    # recipe (see design discussion: pancakes are saved as a meal but
    # still benefit from having steps/a source).
    op.add_column(
        "recipes",
        sa.Column("source_url", sa.String(), nullable=True),
    )


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column("recipes", "source_url")