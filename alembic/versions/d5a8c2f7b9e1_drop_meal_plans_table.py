"""drop meal_plans table

Revision ID: d5a8c2f7b9e1
Revises: c7f2a9d4e6b3
Create Date: 2026-07-20 00:00:00.000000

"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op


# revision identifiers, used by Alembic.
revision: str = 'd5a8c2f7b9e1'
down_revision: Union[str, Sequence[str], None] = 'c7f2a9d4e6b3'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    # The Meal Plan feature (draft-then-commit a whole week before
    # eating any of it) is being removed - pre-logging real meals
    # directly into the Journal already serves the same planning need in
    # practice (see design discussion), so the separate staging area
    # wasn't earning its keep. This table backed that feature only -
    # nothing else reads from or writes to it.
    op.drop_table("meal_plans")


def downgrade() -> None:
    """Downgrade schema."""
    op.create_table(
        "meal_plans",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("date", sa.Date(), nullable=False),
        sa.Column("meal_type", sa.String(), nullable=False),
        sa.Column("item_id", sa.Integer(), sa.ForeignKey("items.item_id"), nullable=True),
        sa.Column("recipe_id", sa.Integer(), sa.ForeignKey("recipes.recipe_id"), nullable=True),
        sa.Column("serving_size_id", sa.Integer(), sa.ForeignKey("serving_sizes.id"), nullable=True),
        sa.Column("quantity", sa.Numeric(), nullable=False),
        sa.CheckConstraint(
            "(item_id IS NOT NULL AND recipe_id IS NULL) OR "
            "(item_id IS NULL AND recipe_id IS NOT NULL)",
            name="ck_meal_plans_item_or_recipe",
        ),
        sa.CheckConstraint(
            "meal_type IN ('breakfast', 'lunch', 'dinner', 'snack')",
            name="ck_meal_plans_meal_type",
        ),
    )