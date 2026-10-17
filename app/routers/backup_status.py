import time
from pathlib import Path
from typing import Optional

from fastapi import APIRouter, Depends
from pydantic import BaseModel

from app.auth import require_api_key
from app.config import settings

router = APIRouter(
    prefix="/backup",
    tags=["backup"],
    dependencies=[Depends(require_api_key)],
)

OFFSITE_MAX_AGE_S = 25 * 3600
DB_DUMP_MAX_AGE_S = 3 * 3600


class BackupCheck(BaseModel):
    ok: bool
    last_success_at: Optional[int] = None  # unix seconds
    message: Optional[str] = None


class BackupStatus(BaseModel):
    ok: bool
    offsite: BackupCheck
    database: BackupCheck


def _read_int(path: Path) -> Optional[int]:
    try:
        return int(path.read_text().strip())
    except (OSError, ValueError):
        return None


def _read_text(path: Path) -> Optional[str]:
    try:
        text = path.read_text().strip()
    except OSError:
        return None
    return text or None


def _check_offsite(now: int) -> BackupCheck:
    status_dir = Path(settings.backup_status_dir)
    last_success = _read_int(status_dir / "offsite-last-success")
    error = _read_text(status_dir / "offsite-error")

    if error:
        return BackupCheck(ok=False, last_success_at=last_success, message=error[:300])
    if last_success is None:
        return BackupCheck(ok=False, message="No off-site backup has completed yet")
    if now - last_success > OFFSITE_MAX_AGE_S:
        return BackupCheck(ok=False, last_success_at=last_success, message="Off-site backup is overdue")
    return BackupCheck(ok=True, last_success_at=last_success)


def _check_database(now: int) -> BackupCheck:
    dump_dir = Path(settings.db_backups_dir)
    latest: Optional[int] = None
    if dump_dir.is_dir():
        for path in dump_dir.rglob("*"):
            try:
                if path.is_file():
                    mtime = int(path.stat().st_mtime)
                    if latest is None or mtime > latest:
                        latest = mtime
            except OSError:
                continue

    if latest is None:
        return BackupCheck(ok=False, message="No database backup found")
    if now - latest > DB_DUMP_MAX_AGE_S:
        return BackupCheck(ok=False, last_success_at=latest, message="Database backup is overdue")
    return BackupCheck(ok=True, last_success_at=latest)


@router.get("/status", response_model=BackupStatus)
def backup_status():
    now = int(time.time())
    offsite = _check_offsite(now)
    database = _check_database(now)
    return BackupStatus(ok=offsite.ok and database.ok, offsite=offsite, database=database)