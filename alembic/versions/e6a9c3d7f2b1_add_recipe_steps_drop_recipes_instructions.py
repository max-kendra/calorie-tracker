"""add recipe steps, drop recipes.instructions

Revision ID: e6a9c3d7f2b1
Revises: d1f5b8c2a7e4
Create Date: 2026-09-27 00:00:00.000000

"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op


revision: str = 'e6a9c3d7f2b1'
down_revision: Union[str, Sequence[str], None] = 'd1f5b8c2a7e4'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    # Dropped in favor of recipe_steps below - nothing has ever written
    # to this column (no UI ever touched it), so there's no data to
    # preserve, and keeping an empty, unused column next to the thing
    # that actually replaced it would just be dead weight (see design
    # discussion).
    op.drop_column("recipes", "instructions")

    op.create_table(
        "recipe_steps",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("recipe_id", sa.Integer(), sa.ForeignKey("recipes.recipe_id", ondelete="CASCADE"), nullable=False),
        sa.Column("step_number", sa.Integer(), nullable=False),
        sa.Column("text", sa.Text(), nullable=False),
        # Nullable - most steps won't have one. Added now even though
        # the UI for it may come later (see design discussion) - cheap
        # to have sit unused, annoying to retrofit after the fact.
        sa.Column("timer_seconds", sa.Integer(), nullable=True),
    )


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_table("recipe_steps")
    op.add_column("recipes", sa.Column("instructions", sa.Text(), nullable=True))