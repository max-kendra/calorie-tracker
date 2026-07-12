from fastapi import Header, HTTPException, status

from app.config import settings


async def require_api_key(x_api_key: str = Header(...)):
    """
    FastAPI dependency — checks the X-API-Key header against our configured
    key. Simple shared-secret auth, appropriate for a single-user personal
    app reachable only over Tailscale — not building out OAuth2/JWT since
    there's no multi-user, third-party-delegation, or token-expiry need here.
    """
    if x_api_key != settings.api_key:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or missing API key",
        )
