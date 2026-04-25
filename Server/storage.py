"""文件存储模块"""
import os
import json
import logging
import threading
import re
from datetime import datetime
from pathlib import Path
from flask import current_app

logger = logging.getLogger(__name__)

# 支持的图片和视频格式
IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".heic", ".heif"}
VIDEO_EXTS = {".mp4", ".mov", ".avi", ".mkv", ".webm", ".3gp", ".mts"}
SUPPORTED_EXTS = IMAGE_EXTS | VIDEO_EXTS

class PhotoStorage:
    """照片/视频存储管理器"""

    def __init__(self, base_dir: str):
        self.base_dir = Path(base_dir)
        self.base_dir.mkdir(parents=True, exist_ok=True)
        self._manifest_path = self.base_dir / "manifest.json"
        self._lock = threading.Lock()
        logger.info(f"PhotoStorage initialized: {self.base_dir}")

    def _load_manifest(self) -> dict:
        """加载 manifest 文件"""
        if not self._manifest_path.exists():
            return {}
        try:
            with open(self._manifest_path, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception as e:
            logger.warning(f"Failed to load manifest: {e}")
            return {}

    def _save_manifest(self, manifest: dict):
        """保存 manifest 文件"""
        with self._lock:
            with open(self._manifest_path, "w", encoding="utf-8") as f:
                json.dump(manifest, f, ensure_ascii=False, indent=2)

    def is_supported(self, filename: str) -> bool:
        """检查文件格式是否支持"""
        ext = Path(filename).suffix.lower()
        return ext in SUPPORTED_EXTS

    def _parse_date_from_name(self, original_name: str):
        """
        从文件名中解析拍摄日期。
        规则：从第4个字符开始，支持 YYYYMMDD 格式（可有下划线前缀）。
        例如: IMG_20240115.jpg, IMG20240115.jpg, VID_20240115_143022.mp4
        如果解析失败，返回 None。
        """
        try:
            name_without_ext = Path(original_name).stem
            # 从第4个字符开始匹配 YYYYMMDD，下划线可有可无
            # IMG_20240115 -> IMG + _ + 20240115
            # IMG20240115  -> IMG + 20240115 (无下划线)
            m = re.match(r'^.{3}_?(\d{8})', name_without_ext)
            if m:
                date_str = m.group(1)
                year = int(date_str[0:4])
                month = int(date_str[4:6])
                day = int(date_str[6:8])
                # 简单校验
                if 2000 <= year <= 2100 and 1 <= month <= 12 and 1 <= day <= 31:
                    return datetime(year, month, day)
        except Exception as e:
            logger.debug(f"Failed to parse date from '{original_name}': {e}")
        return None

    def get_date_path(self, timestamp: int = None) -> Path:
        """根据时间戳获取日期目录路径"""
        try:
            if timestamp and timestamp > 0:
                dt = datetime.fromtimestamp(timestamp)
            else:
                dt = datetime.now()
        except (OSError, ValueError, OverflowError):
            dt = datetime.now()

        date_path = self.base_dir / f"{dt.year}" / f"{dt.month:02d}" / f"{dt.day:02d}"
        date_path.mkdir(parents=True, exist_ok=True)
        return date_path

    def get_date_path_from_name(self, original_name: str) -> Path:
        """从文件名解析日期获取目录路径，失败则用当前日期"""
        dt = self._parse_date_from_name(original_name)
        if dt is None:
            dt = datetime.now()
            logger.debug(f"Could not parse date from '{original_name}', using current date")
        date_path = self.base_dir / f"{dt.year}" / f"{dt.month:02d}" / f"{dt.day:02d}"
        date_path.mkdir(parents=True, exist_ok=True)
        return date_path

    def save_photo(self, file_data: bytes, original_name: str, timestamp: int = None) -> dict:
        """
        保存照片/视频文件

        Args:
            file_data: 文件二进制数据
            original_name: 原始文件名
            timestamp: 照片拍摄时间戳，用于归档

        Returns:
            保存后的信息 dict
        """
        # 检查文件格式
        ext = Path(original_name).suffix.lower()
        if ext not in SUPPORTED_EXTS:
            raise ValueError(f"Unsupported file type: {ext}")

        # 根据文件名解析拍摄日期，确定目录
        date_path = self.get_date_path_from_name(original_name)

        # 使用原始文件名，如果冲突则加时间戳
        base_name = Path(original_name).stem
        file_path = date_path / original_name
        counter = 1
        while file_path.exists():
            # 同名冲突，在文件名后加时间戳
            new_name = f"{base_name}_{timestamp or int(datetime.now().timestamp())}{ext}"
            file_path = date_path / new_name
            # 防止死循环：如果加时间戳后还冲突，继续加序号
            if file_path.exists():
                new_name = f"{base_name}_{timestamp or int(datetime.now().timestamp())}_{counter}{ext}"
                file_path = date_path / new_name
                counter += 1

        with open(file_path, "wb") as f:
            f.write(file_data)

        file_size = file_path.stat().st_size
        relative_path = str(file_path.relative_to(self.base_dir))

        # 判断文件类型
        file_type = "video" if ext in VIDEO_EXTS else "image"

        # 记录到 manifest（用 original_name 作为 key）
        manifest = self._load_manifest()
        manifest[original_name] = {
            "saved_name": file_path.name,
            "size": file_size,
            "type": file_type,
            "timestamp": timestamp,
            "saved_path": relative_path
        }
        self._save_manifest(manifest)

        return {
            "original_name": original_name,
            "saved_name": file_path.name,
            "path": relative_path,
            "size": file_size,
            "timestamp": timestamp,
            "type": file_type
        }
    
    def get_storage_stats(self) -> dict:
        """获取存储统计信息"""
        total_files = 0
        total_size = 0
        image_count = 0
        video_count = 0

        for root, dirs, files in os.walk(self.base_dir):
            for f in files:
                fp = Path(root) / f
                ext = fp.suffix.lower()
                total_size += fp.stat().st_size
                total_files += 1
                if ext in IMAGE_EXTS:
                    image_count += 1
                elif ext in VIDEO_EXTS:
                    video_count += 1

        return {
            "total_files": total_files,
            "image_count": image_count,
            "video_count": video_count,
            "total_size_bytes": total_size,
            "total_size_mb": round(total_size / (1024 * 1024), 2),
            "base_dir": str(self.base_dir)
        }

    def list_existing_files(self) -> dict:
        """列出所有已存储的文件（用于增量同步），返回 original_name -> {size, mtime}"""
        manifest = self._load_manifest()
        files = {}

        # 优先从 manifest 获取信息（包含 original_name）
        for original_name, info in manifest.items():
            saved_path = info.get("saved_path", "")
            full_path = self.base_dir / saved_path
            if full_path.exists():
                files[original_name] = {
                    "size": info.get("size", 0),
                    "mtime": full_path.stat().st_mtime,
                    "saved_path": saved_path
                }
            else:
                # 文件被删除了，从 manifest 标记但文件不在
                files[original_name] = {
                    "size": info.get("size", 0),
                    "mtime": 0,
                    "saved_path": saved_path
                }

        return files
