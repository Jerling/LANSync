"""JWT Token 管理"""
import jwt
import yaml
import json
import hashlib
import os
from pathlib import Path
from datetime import datetime, timedelta, timezone
from functools import wraps
from flask import request, jsonify


def load_config(config_path: str = "config.yaml") -> dict:
    """加载配置文件"""
    with open(config_path, "r", encoding="utf-8") as f:
        return yaml.safe_load(f)


def create_token(username: str, config: dict) -> str:
    """生成 JWT Token"""
    payload = {
        "username": username,
        "exp": datetime.now(timezone.utc) + timedelta(hours=config["auth"]["token_expire_hours"]),
        "iat": datetime.now(timezone.utc),
    }
    return jwt.encode(payload, config["auth"]["jwt_secret"], algorithm="HS256")


def verify_token(token: str, config: dict) -> dict | None:
    """验证 Token，返回 payload 或 None"""
    try:
        payload = jwt.decode(token, config["auth"]["jwt_secret"], algorithms=["HS256"])
        return payload
    except (jwt.ExpiredSignatureError, jwt.InvalidTokenError):
        return None


def require_auth(config: dict):
    """装饰器：验证请求的 JWT Token（支持 header 或 query param）"""
    def decorator(f):
        @wraps(f)
        def decorated(*args, **kwargs):
            # 优先从 query param 取 token（让 Coil 图片请求能携带 token）
            token = request.args.get("token", "")
            if not token:
                auth_header = request.headers.get("Authorization", "")
                if not auth_header.startswith("Bearer "):
                    return jsonify({"error": "Missing or invalid Authorization header"}), 401
                token = auth_header[7:]
            payload = verify_token(token, config)
            if payload is None:
                return jsonify({"error": "Invalid or expired token"}), 401
            request.user = payload["username"]
            return f(*args, **kwargs)
        return decorated
    return decorator


def verify_user(username: str, password: str, config: dict) -> bool:
    """验证用户名密码"""
    for user in config.get("users", []):
        if user["username"] == username and user["password"] == password:
            return True
    return False


_USERS_FILE = Path(__file__).parent / "users.json"

def _load_users() -> dict:
    if not _USERS_FILE.exists():
        return {}
    with open(_USERS_FILE, "r", encoding="utf-8") as f:
        return json.load(f)

def _save_users(users: dict) -> None:
    with open(_USERS_FILE, "w", encoding="utf-8") as f:
        json.dump(users, f, ensure_ascii=False, indent=2)

def _hash_password(password: str, salt: str) -> str:
    return hashlib.sha256((password + salt).encode()).hexdigest()

def register_user(username: str, password: str) -> dict:
    """注册新用户，返回 (success, message)"""
    if not username or not password:
        return False, "用户名和密码不能为空"
    if len(username) < 3:
        return False, "用户名至少3个字符"
    if len(password) < 6:
        return False, "密码至少6个字符"
    if not username.isalnum():
        return False, "用户名只能包含字母和数字"

    users = _load_users()
    if username in users:
        return False, "用户名已存在"

    salt = os.urandom(16).hex()
    users[username] = {
        "salt": salt,
        "password_hash": _hash_password(password, salt),
    }
    _save_users(users)
    return True, "注册成功"

def verify_user_v2(username: str, password: str) -> bool:
    """验证注册用户"""
    users = _load_users()
    if username not in users:
        return False
    u = users[username]
    return u["password_hash"] == _hash_password(password, u["salt"])
