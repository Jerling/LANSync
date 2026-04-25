"""LANSync Server - 局域网照片同步服务端"""
import os
import sys
import logging
import yaml
from pathlib import Path

# 添加当前目录到 Python 路径
sys.path.insert(0, str(Path(__file__).parent))

from flask import Flask
from storage import PhotoStorage
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
    
    # 配置上传大小限制
    max_size = cfg["storage"].get("max_file_size_mb", 500)
    app.config["MAX_CONTENT_LENGTH"] = max_size * 1024 * 1024
    
    # 初始化存储
    storage_base = cfg["storage"]["base_dir"]
    photo_storage = PhotoStorage(storage_base)
    
    # 注册路由
    setup_routes(app, photo_storage)
    
    logger.info(f"LANSync Server started on {cfg['server']['host']}:{cfg['server']['port']}")
    logger.info(f"Storage base directory: {storage_base}")
    
    return app

if __name__ == "__main__":
    cfg = load_config()
    app = create_app()
    app.run(
        host=cfg["server"]["host"],
        port=cfg["server"]["port"],
        debug=cfg["server"]["debug"]
    )
