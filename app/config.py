from pydantic_settings import BaseSettings, SettingsConfigDict

class Settings(BaseSettings):
    default_timezone: str = "Europe/Copenhagen"
    api_key: str
    # Get a free key at https://fdc.nal.usda.gov/api-key-signup - DEMO_KEY
    # works for initial testing but is heavily rate-limited (30/hr, 50/day)
    usda_api_key: str = "DEMO_KEY"
    # Where uploaded product photos get saved (see POST /items/scan-product-photo).
    # Relative paths are resolved relative to the working directory the app
    # runs from (== /app in the Docker image, see docker-compose.yml's
    # `./media:/app/media` volume mount - that's what makes these photos
    # survive a container rebuild instead of living only in the ephemeral
    # container filesystem).
    media_dir: str = "media"
    # Where OCR scan metrics (timing, memory) get written - see
    # app/ocr_metrics.py. Same resolution rules as media_dir above;
    # defaults to a local folder but can point anywhere, including a
    # mounted external drive, if you'd rather spare the SD card the
    # write volume (see docker-compose.yml's ocr_metrics volume mount).
    ocr_metrics_dir: str = "ocr_metrics"

    postgres_user: str
    postgres_password: str
    postgres_db: str
    postgres_host: str = "postgres"
    postgres_port: int = 5432

    @property
    def database_url(self) -> str:
        return f"postgresql://{self.postgres_user}:{self.postgres_password}@{self.postgres_host}:{self.postgres_port}/{self.postgres_db}"

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

settings = Settings()