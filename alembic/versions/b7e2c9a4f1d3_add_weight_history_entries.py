"""add weight history entries

Revision ID: b7e2c9a4f1d3
Revises: a3c8f2e1d4b7
Create Date: 2026-09-27 00:00:00.000000

"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op


revision: str = 'b7e2c9a4f1d3'
down_revision: Union[str, Sequence[str], None] = 'a3c8f2e1d4b7'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.create_table(
        "weight_history_entries",
        sa.Column("id", sa.Integer(), primary_key=True),
        # The READING's own timestamp (from Health Connect's WeightRecord.time),
        # not when we happened to sync it - what a weight chart should
        # actually plot against.
        sa.Column("recorded_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("weight_kg", sa.Numeric(), nullable=False),
    )


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_table("weight_history_entries")