"""add full_ingredients to recipes

Revision ID: d3e8a1f5b7c9
Revises: c5e1a7d3b9f2
Create Date: 2026-10-27 00:00:00.000000

"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op


# revision identifiers, used by Alembic.
revision: str = 'd3e8a1f5b7c9'
down_revision: Union[str, Sequence[str], None] = 'c5e1a7d3b9f2'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.add_column("recipes", sa.Column("full_ingredients", sa.Text(), nullable=True))


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column("recipes", "full_ingredients")
