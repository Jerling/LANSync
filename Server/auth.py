"""JWT Token 管理"""
import jwt
import yaml
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
