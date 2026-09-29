"""repair grocery_list_entries.quantity if it never actually got added

Revision ID: a9d3f7c1b5e8
Revises: f3b7e1a9c5d2
Create Date: 2026-09-29 00:00:00.000000

Diagnostic note: alembic reported this deployment already at head
(f3b7e1a9c5d2), which should mean c4d8a6b3e0f9 (the original migration
adding this column) had already run - but the live database was
missing the column regardless (500 error: "column
grocery_list_entries.quantity does not exist"). alembic_version only
stores a revision id string; it can't detect that a migration's file
content diverged from what actually ran. Rather than chase the exact
cause, this repair is unconditional and idempotent (IF NOT EXISTS), so
it's safe to run whether the column is truly missing or this turns out
to be a red herring.
"""
from typing import Sequence, Union

from alembic import op


revision: str = 'a9d3f7c1b5e8'
down_revision: Union[str, Sequence[str], None] = 'f3b7e1a9c5d2'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.execute("ALTER TABLE grocery_list_entries ADD COLUMN IF NOT EXISTS quantity VARCHAR")


def downgrade() -> None:
    """Downgrade schema."""
    # Deliberately a no-op - this migration only repairs a column that
    # should already have existed from c4d8a6b3e0f9; downgrading it
    # would incorrectly also undo that original migration's intent.
    pass