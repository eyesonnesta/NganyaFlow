from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict

SERVER_ROOT = Path(__file__).resolve().parents[2]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="NGANYAFLOW_", env_file=SERVER_ROOT / ".env", extra="ignore"
    )
    database: Path = Path("data/raw/observations.sqlite3")
    catalog: Path = Path("data/reference/catalog.json")
    write_token: str = ""
    model_path: Path | None = None
    max_evidence_age_minutes: int = Field(default=30, ge=1)
    max_model_age_days: int = Field(default=30, ge=1)
    min_recent_arrivals: int = Field(default=3, ge=2)
    runtime_dir: Path = Path("runtime")

    def resolve(self, path: Path) -> Path:
        return path if path.is_absolute() else SERVER_ROOT / path
