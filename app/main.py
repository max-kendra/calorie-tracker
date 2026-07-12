from fastapi import FastAPI

from app.routers import goals, items, logs, meal_plans, recipes, usda, user_profile

app = FastAPI(
    title="Meal Tracker API",
    description="Self-hosted meal tracker, recipe manager, and meal planner backend.",
    version="0.1.0",
)

app.include_router(items.router)
app.include_router(recipes.router)
app.include_router(logs.router)
app.include_router(meal_plans.router)
app.include_router(goals.router)
app.include_router(user_profile.router)
app.include_router(usda.router)


@app.get("/health", tags=["meta"])
def health_check():
    """Unauthenticated — just confirms the API process is up."""
    return {"status": "ok"}