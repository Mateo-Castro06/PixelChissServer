from dataclasses import dataclass
from functools import lru_cache
from typing import Optional

import jwt
from fastapi import Depends, HTTPException
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.core import config

bearer = HTTPBearer(auto_error=False)

@dataclass
class CurrentUser:
    id: str
    email: str

@lru_cache
def _public_key():
    if not config.JWT_PUBLIC_KEY_PATH:
        raise RuntimeError("Falta la variable de entorno JWT_PUBLIC_KEY_PATH")
    with open(config.JWT_PUBLIC_KEY_PATH, encoding="utf-8") as f:
        return f.read()

def get_current_user(
        credentials: Optional[HTTPAuthorizationCredentials] = Depends(bearer),
):
    if credentials is None:
        raise HTTPException(status_code=401, detail="Falta el token de acceso")
    try:
        payload = jwt.decode(credentials.credentials, _public_key(), algorithms=["RS256"])
    except jwt.ExpiredSignatureError:
        raise HTTPException(status_code=401, detail="Sesion expirada, inicia sesion nuevamente")
    except jwt.PyJWTError:
        raise HTTPException(status_code=401, detail="Token invalido")
    return CurrentUser(id=payload["sub"], email=payload.get("email", ""))