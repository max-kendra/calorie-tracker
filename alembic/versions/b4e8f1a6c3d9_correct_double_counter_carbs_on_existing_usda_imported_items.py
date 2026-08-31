"""correct double-counted carbs on existing usda-imported items

Revision ID: b4e8f1a6c3d9
Revises: a3c7e9f2d4b1
Create Date: 2026-07-20 00:00:00.000000

"""
from typing import Sequence, Union

from alembic import op


# revision identifiers, used by Alembic.
revision: str = 'b4e8f1a6c3d9'
down_revision: Union[str, Sequence[str], None] = 'a3c7e9f2d4b1'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    # One-time data correction, not a schema change (see app/usda.py's
    # extract_macros for the actual fix going forward). USDA's
    # "Carbohydrate, by difference" is TOTAL carbohydrate, which by
    # definition already includes fiber as a subset - same as any US
    # nutrition label's "Total Carbohydrate" followed by an indented "of
    # which Dietary Fiber" sub-line, not two independent, additive
    # nutrients. This app's own carbs_100g convention is NET carbs
    # (excluding fiber), matching how EU nutrition labels are entered
    # everywhere else (EU labels list fiber as its own separate line,
    # not a subset of carbs). Every item imported from USDA before this
    # fix has carbs_100g stored as USDA's gross/total figure, double-
    # counting fiber whenever the app treats carbs/fiber as independent
    # totals (see design discussion: "my macros and calories in my
    # weekly summaries don't add up... i'm literally duplicating the
    # carbs and fiber"). Corrects those existing rows the same way the
    # code fix does: subtract fiber_100g from carbs_100g, floored at 0.
    # Only touches origin='usda_import' rows with both fields present -
    # manually-entered/scanned items already follow the net-carbs
    # convention and must NOT be touched here.
    op.execute(
        """
        UPDATE items
        SET carbs_100g = GREATEST(carbs_100g - fiber_100g, 0)
        WHERE origin = 'usda_import'
          AND carbs_100g IS NOT NULL
          AND fiber_100g IS NOT NULL
        """
    )


def downgrade() -> None:
    """Downgrade schema."""
    # Not reversible - the original gross/total carbs figure isn't
    # retained anywhere once subtracted. Re-running the (now-corrected)
    # upgrade a second time would incorrectly subtract fiber again, so
    # this intentionally does nothing rather than guess.
    pass