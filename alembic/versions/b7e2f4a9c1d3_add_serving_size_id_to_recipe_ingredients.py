"""add serving_size_id to recipe_ingredients, rename quantity_g to quantity

Revision ID: b7e2f4a9c1d3
Revises: f3a8c1d9e0b2
Create Date: 2026-07-19 00:00:00.000000

"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op


# revision identifiers, used by Alembic.
revision: str = 'b7e2f4a9c1d3'
down_revision: Union[str, Sequence[str], None] = 'f3a8c1d9e0b2'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    # recipe_ingredients previously only stored a flat gram amount, with
    # no way to remember that an ingredient was originally entered as
    # e.g. "2 pancakes" rather than "150g" -- same quantity/serving_size
    # dual semantics LoggableEntryBase already uses for logs/meal_plans
    # (see that schema's docstring): with no serving_size_id, quantity is
    # grams directly; with one set, quantity is a multiplier of that
    # serving's weight_g.
    op.add_column(
        "recipe_ingredients",
        sa.Column("serving_size_id", sa.Integer(), sa.ForeignKey("serving_sizes.id"), nullable=True),
    )
    op.alter_column("recipe_ingredients", "quantity_g", new_column_name="quantity")


def downgrade() -> None:
    """Downgrade schema."""
    op.alter_column("recipe_ingredients", "quantity", new_column_name="quantity_g")
    op.drop_column("recipe_ingredients", "serving_size_id")