FROM python:3.11-slim

WORKDIR /app

# Install Poetry
RUN pip install --no-cache-dir poetry==1.8.3

# Copy dependency files first so Docker can cache this layer -- rebuilds
# only re-install deps when pyproject.toml/poetry.lock actually change,
# not on every code edit.
COPY pyproject.toml poetry.lock* ./

# --no-root: don't try to install this project itself as a package (it's
# not structured as one -- see pyproject.toml's package-mode = false)
# --only main: skip dev dependencies (pytest etc) in the production image
RUN poetry config virtualenvs.create false \
    && poetry install --no-root --only main --no-interaction --no-ansi

# Now copy the actual application code
COPY app/ ./app/
COPY alembic/ ./alembic/
COPY alembic.ini ./

EXPOSE 8000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]