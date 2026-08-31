"""seed physiological guidelines

Revision ID: 824c17bcbba4
Revises: 1b9cc4fdfc9b
Create Date: 2026-07-12 15:39:29.822117

Seeds population-level reference ranges for the macros we track, used
by the (future) warning system that compares an active GOAL against
these ranges - not the daily log directly (see design doc: avoids false
warnings on days the user simply ate less than their goal).

Sources (checked against current guidance, not just recalled from
training data):
  - 2025-2030 Dietary Guidelines for Americans (protein, saturated fat,
    added sugar, fiber, sodium upper limit)
  - WHO guidance (added sugar conditional recommendation, sodium)
  - U.S./Canadian Dietary Reference Intakes - Acceptable Macronutrient
    Distribution Range, AMDR (fat and carbohydrate as % of calories)
  - Institute of Medicine adequate-intake benchmark (fiber per 1000 kcal)

These are POPULATION-LEVEL reference ranges, not personalized advice -
they're a sanity-check backdrop for a user's own goals, not a substitute
for professional guidance. Adding a unique constraint on `name` since
this table should have exactly one row per guideline; also makes this
migration safely re-runnable in spirit (though Alembic migrations only
ever run once per DB, tracked in alembic_version, so re-application
isn't actually expected in normal use).
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '824c17bcbba4'
down_revision: Union[str, Sequence[str], None] = '1b9cc4fdfc9b'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


guidelines_table = sa.table(
    "physiological_guidelines",
    sa.column("name", sa.String),
    sa.column("min_value", sa.Numeric),
    sa.column("recommended_value", sa.Numeric),
    sa.column("max_value", sa.Numeric),
    sa.column("unit", sa.String),
    sa.column("basis", sa.Text),
)

GUIDELINES = [
    {
        "name": "protein_per_kg_bodyweight",
        "min_value": 0.8,
        "recommended_value": 1.4,
        "max_value": 2.2,
        "unit": "g/kg/day",
        "basis": (
            "US RDA minimum 0.8 g/kg/day (sedentary baseline); "
            "2025-2030 Dietary Guidelines for Americans recommend "
            "1.2-1.6 g/kg/day for general adults (1.4 used as midpoint "
            "here); upper end ~2.2 g/kg/day reflects sports-nutrition "
            "literature for very active individuals / strength training."
        ),
    },
    {
        "name": "fat_pct_of_kcal",
        "min_value": 20,
        "recommended_value": 27.5,
        "max_value": 35,
        "unit": "% of kcal/day",
        "basis": (
            "Acceptable Macronutrient Distribution Range (AMDR), "
            "US/Canadian Dietary Reference Intakes. 27.5 used as midpoint."
        ),
    },
    {
        "name": "saturated_fat_pct_of_kcal",
        "min_value": 0,
        "recommended_value": None,
        "max_value": 10,
        "unit": "% of kcal/day",
        "basis": (
            "2025-2030 Dietary Guidelines for Americans: keep saturated "
            "fat under 10% of total daily calories, starting at age 2. "
            "Consistent with WHO guidance."
        ),
    },
    {
        "name": "carbs_pct_of_kcal",
        "min_value": 45,
        "recommended_value": 55,
        "max_value": 65,
        "unit": "% of kcal/day",
        "basis": (
            "Acceptable Macronutrient Distribution Range (AMDR), "
            "US/Canadian Dietary Reference Intakes. 55 used as midpoint."
        ),
    },
    {
        "name": "fiber_per_1000kcal",
        "min_value": None,
        "recommended_value": 14,
        "max_value": None,
        "unit": "g/1000kcal/day",
        "basis": (
            "Institute of Medicine adequate-intake benchmark (14g fiber "
            "per 1000 kcal), cited consistently in Dietary Guidelines "
            "for Americans editions."
        ),
    },
    {
        "name": "added_sugar_pct_of_kcal",
        "min_value": 0,
        "recommended_value": 5,
        "max_value": 10,
        "unit": "% of kcal/day",
        "basis": (
            "2025-2030 Dietary Guidelines for Americans: keep added "
            "sugar under 10% of total daily calories (used as max here). "
            "WHO's conditional recommendation of under 5% for additional "
            "health benefit is used as the 'recommended' target."
        ),
    },
    {
        "name": "sodium_mg_per_day",
        "min_value": None,
        "recommended_value": 2000,
        "max_value": 2300,
        "unit": "mg/day",
        "basis": (
            "2300mg/day is the US Dietary Guidelines for Americans "
            "upper limit (used as max here); WHO recommends under "
            "2000mg/day for adults (used as the 'recommended' target, "
            "the stricter of the two)."
        ),
    },
]


def upgrade() -> None:
    op.create_unique_constraint(
        "uq_physiological_guidelines_name", "physiological_guidelines", ["name"]
    )
    op.bulk_insert(guidelines_table, GUIDELINES)


def downgrade() -> None:
    op.execute(
        "DELETE FROM physiological_guidelines WHERE name IN ("
        + ",".join(f"'{g['name']}'" for g in GUIDELINES)
        + ")"
    )
    op.drop_constraint(
        "uq_physiological_guidelines_name", "physiological_guidelines", type_="unique"
    )