"""add recipe_components so saved meals can contain recipes

Revision ID: c5e1a7d3b9f2
Revises: a9d3f7c1b5e8
Create Date: 2026-10-09 00:00:00.000000

"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op


revision: str = 'c5e1a7d3b9f2'
down_revision: Union[str, Sequence[str], None] = 'a9d3f7c1b5e8'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "recipe_components",
        sa.Column("recipe_id", sa.Integer(), sa.ForeignKey("recipes.recipe_id", ondelete="CASCADE"), primary_key=True),
        sa.Column("component_recipe_id", sa.Integer(), sa.ForeignKey("recipes.recipe_id"), primary_key=True),
        sa.Column("quantity", sa.Numeric(), nullable=False),
        sa.CheckConstraint("recipe_id <> component_recipe_id", name="ck_recipe_components_not_self"),
    )


def downgrade() -> None:
    op.drop_table("recipe_components")