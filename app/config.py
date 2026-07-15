from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    database_url: str
    default_timezone: str = "Europe/Copenhagen"
    api_key: str
    # Get a free key at https://fdc.nal.usda.gov/api-key-signup -- DEMO_KEY
    # works for initial testing but is heavily rate-limited (30/hr, 50/day)
    usda_api_key: str = "DEMO_KEY"
    # Where uploaded product photos get saved (see POST /items/scan-product-photo).
    # Relative paths are resolved relative to the working directory the app
    # runs from (== /app in the Docker image, see docker-compose.yml's
    # `./media:/app/media` volume mount -- that's what makes these photos
    # survive a container rebuild instead of living only in the ephemeral
    # container filesystem).
    media_dir: str = "media"

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")


settings = Settings()