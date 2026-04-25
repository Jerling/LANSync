"""认证模块 - JWT Token 管理"""
import jwt
import yaml
from datetime import datetime, timedelta
from functools import wraps
from flask import request, jsonify

_config = None

def load_config():
    global _config
    if _config is None:
        with open("config.yaml", "r", encoding="utf-8") as f:
            _config = yaml.safe_load(f)
    return _config

def create_token(username: str) -> str:
    """生成 JWT Token"""
    cfg = load_config()
    payload = {
        "username": username,
        "exp": datetime.utcnow() + timedelta(hours=cfg["auth"]["token_expire_hours"]),
        "iat": datetime.utcnow()
    }
    return jwt.encode(
        payload,
        cfg["auth"]["jwt_secret"],
        algorithm="HS256"
    )

def verify_token(token: str) -> dict | None:
    """验证 Token，返回 payload 或 None"""
    cfg = load_config()
    try:
        payload = jwt.decode(
            token,
            cfg["auth"]["jwt_secret"],
            algorithms=["HS256"]
        )
        return payload
    except jwt.ExpiredSignatureError:
        return None
    except jwt.InvalidTokenError:
        return None

def require_auth(f):
    """装饰器：验证请求的 JWT Token"""
    @wraps(f)
    def decorated(*args, **kwargs):
        auth_header = request.headers.get("Authorization", "")
        if not auth_header.startswith("Bearer "):
            return jsonify({"error": "Missing or invalid Authorization header"}), 401
        
        token = auth_header[7:]  # 去掉 "Bearer " 前缀
        payload = verify_token(token)
        if payload is None:
            return jsonify({"error": "Invalid or expired token"}), 401
        
        request.user = payload["username"]
        return f(*args, **kwargs)
    return decorated

def verify_user(username: str, password: str) -> bool:
    """验证用户名密码"""
    cfg = load_config()
    users = cfg.get("users", [])
    for user in users:
        if user["username"] == username and user["password"] == password:
            return True
    return False
