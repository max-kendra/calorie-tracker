"""enable pg_trgm and add trigram indexes for fuzzy search

Revision ID: f3a8c1d9e0b2
Revises: a1c3f9e2b4d7
Create Date: 2026-07-19 00:00:00.000000

"""
from typing import Sequence, Union

from alembic import op


# revision identifiers, used by Alembic.
revision: str = 'f3a8c1d9e0b2'
down_revision: Union[str, Sequence[str], None] = 'a1c3f9e2b4d7'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    # Needed for the similarity()/% operator used by app/search.py's
    # multi_column_search_filter - lets search tolerate plurals, typos,
    # and near-misses (e.g. "pancakes" finding an item named "Pancake")
    # instead of requiring an exact contiguous substring match.
    op.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm")

    # GIN trigram indexes so similarity()/% actually stays fast as the
    # catalog grows, rather than falling back to a sequential scan for
    # every fuzzy search the way plain ILIKE always has to. Without
    # these, search still works correctly (see search.py's fallback
    # reasoning) - this is purely a performance safety net.
    op.execute(
        "CREATE INDEX IF NOT EXISTS ix_items_name_trgm ON items USING gin (name gin_trgm_ops)"
    )
    op.execute(
        "CREATE INDEX IF NOT EXISTS ix_items_brand_trgm ON items USING gin (brand gin_trgm_ops)"
    )
    op.execute(
        "CREATE INDEX IF NOT EXISTS ix_recipes_name_trgm ON recipes USING gin (name gin_trgm_ops)"
    )


def downgrade() -> None:
    """Downgrade schema."""
    op.execute("DROP INDEX IF EXISTS ix_recipes_name_trgm")
    op.execute("DROP INDEX IF EXISTS ix_items_brand_trgm")
    op.execute("DROP INDEX IF EXISTS ix_items_name_trgm")
    # Extension deliberately left in place on downgrade - dropping it
    # would fail if anything else in the DB still depends on it, and
    # leaving an unused extension installed is harmless.