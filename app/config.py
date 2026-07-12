from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    database_url: str
    default_timezone: str = "Europe/Copenhagen"
    api_key: str

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")


settings = Settings()
