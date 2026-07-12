from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    database_url: str
    default_timezone: str = "Europe/Copenhagen"
    api_key: str
    # Get a free key at https://fdc.nal.usda.gov/api-key-signup -- DEMO_KEY
    # works for initial testing but is heavily rate-limited (30/hr, 50/day)
    usda_api_key: str = "DEMO_KEY"

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")


settings = Settings()