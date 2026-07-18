"""adjust carbs guideline to exclude fiber overlap

Revision ID: 7dec710dc7d5
Revises: 824c17bcbba4
Create Date: 2026-07-12 15:45:13.041043

The standard AMDR carbohydrate range (45-65% of calories) refers to
TOTAL carbohydrate, which by nutritional definition includes fiber -
fiber is a subset of carbohydrate, not a separate macro.

However, this app's goal-setting model (meal_goal_splits, and the
Fat/Protein/Carbs/Fiber split shown in the Macronutrients screen) treats
Carbs and Fiber as INDEPENDENT slices that both count toward the same
100% of calories. That means this app's "Carbs" value actually
represents carbohydrate EXCLUDING fiber, not total carbohydrate as the
AMDR defines it. Using the raw AMDR figures as-is would effectively
double-count fiber's calories once under Carbs and again under Fiber.

Fix: subtract fiber's typical caloric contribution from the AMDR range.
Using the fiber_per_1000kcal guideline (14g/1000kcal) at 2 kcal/g (the
standard nutrition-labeling convention for fiber) works out to ~2.8% of
calories - consistent with the ~3% shown for fiber in the app's own
Macronutrients UI. Subtracting ~2.8 percentage points from each AMDR
bound:
  - min: 45 -> 42
  - recommended: 55 -> 52
  - max: 65 -> 62

This is a deliberate modeling adjustment to match how the app actually
splits these two macros, not a correction of the underlying AMDR source
figures themselves (those remain accurate for TOTAL carbohydrate).
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '7dec710dc7d5'
down_revision: Union[str, Sequence[str], None] = '824c17bcbba4'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

OLD_VALUES = {"min_value": 45, "recommended_value": 55, "max_value": 65}
NEW_VALUES = {"min_value": 42, "recommended_value": 52, "max_value": 62}

NEW_BASIS = (
    "Acceptable Macronutrient Distribution Range (AMDR) for TOTAL "
    "carbohydrate is 45-65% of calories (US/Canadian Dietary Reference "
    "Intakes). This app tracks Carbs and Fiber as independent goal "
    "slices (see meal_goal_splits / Macronutrients screen), so this "
    "app's 'carbs' value excludes fiber - adjusted down by ~2.8 "
    "percentage points (fiber_per_1000kcal's 14g/1000kcal at 2 kcal/g) "
    "to avoid double-counting fiber's calories in both slices."
)

OLD_BASIS = (
    "Acceptable Macronutrient Distribution Range (AMDR), "
    "US/Canadian Dietary Reference Intakes. 55 used as midpoint."
)


def upgrade() -> None:
    op.execute(
        sa.text(
            "UPDATE physiological_guidelines "
            "SET min_value = :min_value, recommended_value = :recommended_value, "
            "max_value = :max_value, basis = :basis "
            "WHERE name = 'carbs_pct_of_kcal'"
        ).bindparams(basis=NEW_BASIS, **NEW_VALUES)
    )


def downgrade() -> None:
    op.execute(
        sa.text(
            "UPDATE physiological_guidelines "
            "SET min_value = :min_value, recommended_value = :recommended_value, "
            "max_value = :max_value, basis = :basis "
            "WHERE name = 'carbs_pct_of_kcal'"
        ).bindparams(basis=OLD_BASIS, **OLD_VALUES)
    )
