"""LANSync Server - 局域网照片同步服务端"""
import os
import sys
import logging
import yaml
from pathlib import Path

# 添加当前目录到 Python 路径
sys.path.insert(0, str(Path(__file__).parent))

from flask import Flask, send_from_directory, request
from flask_limiter import Limiter
from flask_limiter.util import get_remote_address
from handlers import setup_routes

def setup_logging(log_file: str, log_level: str):
    """配置日志"""
    log_dir = Path(log_file).parent
    log_dir.mkdir(parents=True, exist_ok=True)
    
    logging.basicConfig(
        level=getattr(logging, log_level.upper(), logging.INFO),
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        handlers=[
            logging.FileHandler(log_file, encoding="utf-8"),
            logging.StreamHandler()
        ]
    )

def load_config(config_path: str = "config.yaml"):
    """加载配置文件"""
    with open(config_path, "r", encoding="utf-8") as f:
        return yaml.safe_load(f)

def create_app(config_path: str = "config.yaml"):
    """创建并配置 Flask 应用"""
    cfg = load_config(config_path)
    
    # 配置日志
    setup_logging(cfg["logging"]["file"], cfg["logging"]["level"])
    logger = logging.getLogger(__name__)
    
    # 创建 Flask 应用
    app = Flask(__name__)
    
    # 配置限流器
    limiter = Limiter(
        app=app,
        key_func=get_remote_address,
        default_limits=["200 per day", "50 per hour"],
        storage_uri="memory://",
    )
    
    # 配置上传大小限制
    max_size = cfg["storage"].get("max_file_size_mb", 500)
    app.config["MAX_CONTENT_LENGTH"] = max_size * 1024 * 1024
    
    # 注册路由（storage 实例在请求时按用户创建）
    setup_routes(app, cfg, limiter)

    # 静态文件 + SPA fallback
    static_dir = Path(__file__).parent / "static"
    @app.route("/")
    def serve_index():
        return send_from_directory(static_dir, "index.html")
    @app.route("/<path:filename>")
    def serve_static(filename):
        # 文件存在则正常返回，否则让 SPA 处理（Vue Router 路由）
        file_path = static_dir / filename
        if file_path.is_file():
            return send_from_directory(static_dir, filename)
        return send_from_directory(static_dir, "index.html")

    logger.info(f"LANSync Server started on {cfg['server']['host']}:{cfg['server']['port']}")
    logger.info(f"Storage base directory: {cfg['storage']['base_dir']}")
    
    return app

if __name__ == "__main__":
    cfg = load_config()
    app = create_app()
    app.run(
        host=cfg["server"]["host"],
        port=cfg["server"]["port"],
        debug=cfg["server"]["debug"]
    )
