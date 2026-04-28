"""文件存储模块"""
import os
import json
import logging
import threading
import re
import hashlib
from datetime import datetime
from pathlib import Path
from flask import current_app

logger = logging.getLogger(__name__)

# 支持的图片格式（用于缩略图生成）
THUMB_SUPPORTED_EXTS = {".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".heic", ".heif"}

# 支持的图片和视频格式
IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".heic", ".heif"}
VIDEO_EXTS = {".mp4", ".mov", ".avi", ".mkv", ".webm", ".3gp", ".mts"}
SUPPORTED_EXTS = IMAGE_EXTS | VIDEO_EXTS

class PhotoStorage:
    """照片/视频存储管理器"""

    def __init__(self, base_dir: str):
        self.base_dir = self._normalize_path(base_dir)
        self.base_dir.mkdir(parents=True, exist_ok=True)
        self._manifest_path = self.base_dir / "manifest.json"
        self._lock = threading.Lock()
        logger.info(f"PhotoStorage initialized: {self.base_dir}")

    def _normalize_path(self, path: str) -> Path:
        """
        规范化路径，兼容 Windows (D:\\...) 和 WSL (/mnt/d/...) 格式。
        在 WSL 环境下:
          D:\\path 或 D:/path -> /mnt/d/path
        """
        import subprocess
        p = Path(path)
        # 如果路径已经可以正常访问，直接返回
        if p.exists():
            return p
        # 尝试 WSL 路径转换
        try:
            result = subprocess.run(
                ["wsl", "wslpath", "-w", path],
                capture_output=True, text=True, timeout=5
            )
            if result.returncode == 0:
                win_path = result.stdout.strip()
                return Path(win_path)
        except Exception:
            pass
        return p

    def _to_fs_path(self, relative_path: str) -> Path:
        """
        将 manifest.json 中的相对路径转换为实际文件系统路径。
        处理 Windows (D:\\...) 和 Unix (/mnt/d/...) 格式。
        """
        # 先把 Windows 反斜杠统一成正斜杠
        normalized = relative_path.replace("\\", "/")
        return self.base_dir / normalized

    def _load_manifest(self) -> dict:
        """加载 manifest 文件（自动迁移旧格式）"""
        if not self._manifest_path.exists():
            return {}
        try:
            with open(self._manifest_path, "r", encoding="utf-8") as f:
                manifest = json.load(f)

            # 检查是否需要迁移旧格式（key 为 original_name，非 SHA256）
            needs_migration = False
            for key in manifest.keys():
                if len(key) != 64:  # 非 SHA256 key，说明是旧格式
                    needs_migration = True
                    break

            if needs_migration:
                logger.info("Migrating manifest from original_name key to SHA256 key format...")
                new_manifest = {}
                for old_key, info in manifest.items():
                    # 已经64位sha的key直接保留
                    if len(old_key) == 64:
                        new_manifest[old_key] = info
                    else:
                        # 旧格式：key 是 original_name，需要重新计算哈希
                        saved_path = info.get("saved_path", "")
                        full_path = self._to_fs_path(saved_path)
                        if full_path.exists():
                            try:
                                hash_val = self._compute_sha256_stream(full_path)
                                info["original_name"] = old_key  # 确保 original_name 字段存在
                                info["hash"] = hash_val
                                new_manifest[hash_val] = info
                                logger.info(f"  Migrated: {old_key} -> {hash_val[:16]}...")
                            except Exception as e:
                                logger.warning(f"  Failed to migrate {old_key}: {e}, keeping as-is")
                                new_manifest[old_key] = info
                        else:
                            logger.warning(f"  Skipping missing file: {saved_path}")
                manifest = new_manifest
                self._save_manifest(manifest)
                logger.info("Manifest migration completed")

            return manifest
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

    def _compute_sha256(self, data: bytes) -> str:
        """计算二进制数据的 SHA256 哈希（十六进制字符串）"""
        return hashlib.sha256(data).hexdigest()

    def _compute_sha256_stream(self, file_path: Path) -> str:
        """流式计算文件 SHA256（避免大文件内存问题）"""
        sha = hashlib.sha256()
        with open(file_path, "rb") as f:
            for chunk in iter(lambda: f.read(65536), b""):
                sha.update(chunk)
        return sha.hexdigest()

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

        # 计算文件内容的 SHA256（用于内容去重）
        file_hash = self._compute_sha256(file_data)
        file_size = len(file_data)

        # 加载 manifest，检查是否已有同哈希的文件（内容去重）
        manifest = self._load_manifest()
        # 新格式: manifest[hash] -> [entries list]（支持同哈希多文件场景）
        existing_list = manifest.get(file_hash, [])
        if existing_list:
            # 内容已存在，视为成功（幂等上传），返回第一条记录
            existing_by_hash = existing_list[0]
            logger.info(f"File {original_name} already exists (hash={file_hash[:16]}...), skipping duplicate save")
            return {
                "original_name": original_name,
                "saved_name": existing_by_hash.get("saved_name", original_name),
                "path": existing_by_hash.get("saved_path", ""),
                "size": existing_by_hash.get("size", file_size),
                "timestamp": timestamp,
                "type": existing_by_hash.get("type", ("video" if ext in VIDEO_EXTS else "image")),
                "skipped": True,
                "hash": file_hash
            }

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

        actual_size = file_path.stat().st_size
        relative_path = str(file_path.relative_to(self.base_dir))

        # 判断文件类型
        file_type = "video" if ext in VIDEO_EXTS else "image"

        # 用 SHA256 作为 manifest key（内容去重），value 为列表支持同哈希多文件
        if file_hash not in manifest:
            manifest[file_hash] = []
        manifest[file_hash].append({
            "original_name": original_name,
            "saved_name": file_path.name,
            "size": actual_size,
            "type": file_type,
            "timestamp": timestamp,
            "saved_path": relative_path,
            "hash": file_hash
        })
        self._save_manifest(manifest)

        return {
            "original_name": original_name,
            "saved_name": file_path.name,
            "path": relative_path,
            "size": actual_size,
            "timestamp": timestamp,
            "type": file_type,
            "hash": file_hash
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
        """列出所有已存储的文件（用于增量同步），返回 hash -> {original_name, size, mtime, saved_path}

        Manifest 格式为 {hash: [entries list]}（支持同哈希多文件），这里只返回第一个 entry 的基本信息。
        同时构建 (name, size) -> hash 的反向索引，供 name+size 查询用。
        """
        manifest = self._load_manifest()
        files = {}
        # 反向索引: (original_name, size) -> hash，用于 name+size 快速查询
        name_size_index = {}

        for hash_key, entries in manifest.items():
            # 跳过非哈希的旧 key（兼容 manifest 中仍存在的 original_name key）
            if len(hash_key) != 64:
                continue
            # entries 是列表，取第一个 entry
            info = entries[0] if entries else {}
            original_name = info.get("original_name", "")
            size = info.get("size", 0)
            saved_path = info.get("saved_path", "")
            full_path = self._to_fs_path(saved_path)
            mtime = full_path.stat().st_mtime if full_path.exists() else 0

            files[hash_key] = {
                "original_name": original_name,
                "size": size,
                "mtime": mtime,
                "saved_path": saved_path
            }
            # 建立反向索引
            name_size_index[(original_name, size)] = hash_key

        # 将反向索引也放入返回值，key 为特殊前缀以区分
        files["__index__"] = name_size_index
        return files

    # ==================== 分片续传相关 ====================

    def _get_partial_dir(self) -> Path:
        """获取分片暂存目录"""
        partial_dir = self.base_dir / "_partial"
        partial_dir.mkdir(parents=True, exist_ok=True)
        return partial_dir

    def get_partial_path(self, file_id: str) -> Path:
        """根据文件ID获取分片文件路径"""
        return self._get_partial_dir() / f"{file_id}.part"

    def init_partial_upload(self, file_id: str, total_size: int, original_name: str) -> dict:
        """
        初始化分片上传，保存文件元信息到 .meta 文件
        """
        partial_path = self.get_partial_path(file_id)
        meta_path = partial_path.with_suffix(".meta")

        # 如果已存在分片文件，返回已上传大小
        uploaded_size = partial_path.stat().st_size if partial_path.exists() else 0

        # 写入 meta 文件（即使存在也要更新，以防 total_size 变化）
        meta = {
            "file_id": file_id,
            "original_name": original_name,
            "total_size": total_size,
            "uploaded_size": uploaded_size,
        }
        with open(meta_path, "w", encoding="utf-8") as f:
            json.dump(meta, f, ensure_ascii=False)

        return {"uploaded_size": uploaded_size}

    def append_partial(self, file_id: str, chunk_data: bytes) -> dict:
        """
        追加分片数据到临时文件
        """
        partial_path = self.get_partial_path(file_id)
        meta_path = partial_path.with_suffix(".meta")

        # 追加模式写入
        with open(partial_path, "ab") as f:
            f.write(chunk_data)

        # 更新 meta 中的 uploaded_size
        uploaded_size = partial_path.stat().st_size
        if meta_path.exists():
            try:
                with open(meta_path, "r", encoding="utf-8") as f:
                    meta = json.load(f)
                meta["uploaded_size"] = uploaded_size
                with open(meta_path, "w", encoding="utf-8") as f:
                    json.dump(meta, f, ensure_ascii=False)
            except Exception:
                pass

        return {"uploaded_size": uploaded_size}

    def complete_partial_upload(self, file_id: str) -> dict:
        """
        完成分片上传：将 .part 文件 Move 到最终路径，清理 meta
        """
        partial_path = self.get_partial_path(file_id)
        meta_path = partial_path.with_suffix(".meta")

        if not partial_path.exists():
            raise FileNotFoundError(f"Partial file not found: {file_id}")

        # 读取 meta
        meta = {}
        if meta_path.exists():
            with open(meta_path, "r", encoding="utf-8") as f:
                meta = json.load(f)

        original_name = meta.get("original_name", file_id)
        total_size = meta.get("total_size", partial_path.stat().st_size)
        timestamp = meta.get("timestamp")

        # 验证完整性
        actual_size = partial_path.stat().st_size
        if actual_size != total_size:
            raise ValueError(f"Incomplete upload: expected {total_size}, got {actual_size}")

        # 移动到最终路径（复用 save_photo 的目录逻辑）
        ext = Path(original_name).suffix.lower()
        file_type = "video" if ext in VIDEO_EXTS else "image"

        # 确定目标路径
        date_path = self.get_date_path_from_name(original_name)
        base_name = Path(original_name).stem
        final_path = date_path / original_name
        counter = 1
        while final_path.exists():
            new_name = f"{base_name}_{timestamp or int(datetime.now().timestamp())}{ext}"
            final_path = date_path / new_name
            if final_path.exists():
                new_name = f"{base_name}_{timestamp or int(datetime.now().timestamp())}_{counter}{ext}"
                final_path = date_path / new_name
                counter += 1

        # Move 文件
        partial_path.rename(final_path)

        # 清理 meta
        if meta_path.exists():
            meta_path.unlink()

        file_size = final_path.stat().st_size
        relative_path = str(final_path.relative_to(self.base_dir))

        # 计算内容哈希（流式，避免大文件内存问题）
        file_hash = self._compute_sha256_stream(final_path)

        # 内容去重：检查是否已有同哈希文件
        manifest = self._load_manifest()
        existing = manifest.get(file_hash)
        if existing:
            # 内容已存在，删除刚写入的文件，视为幂等成功
            final_path.unlink()
            logger.info(f"Complete partial: {original_name} already exists (hash={file_hash[:16]}...), skipping")
            return {
                "original_name": original_name,
                "saved_name": existing.get("saved_name", original_name),
                "path": existing.get("saved_path", ""),
                "size": existing.get("size", file_size),
                "timestamp": timestamp,
                "type": existing.get("type", file_type),
                "skipped": True,
                "hash": file_hash
            }

        # 记录到 manifest（用 SHA256 作为 key）
        manifest[file_hash] = {
            "original_name": original_name,
            "saved_name": final_path.name,
            "size": file_size,
            "type": file_type,
            "timestamp": timestamp,
            "saved_path": relative_path,
            "hash": file_hash
        }
        self._save_manifest(manifest)

        return {
            "original_name": original_name,
            "saved_name": final_path.name,
            "path": relative_path,
            "size": file_size,
            "timestamp": timestamp,
            "type": file_type,
            "hash": file_hash
        }

    def get_partial_status(self, file_id: str) -> dict:
        """查询分片上传状态"""
        partial_path = self.get_partial_path(file_id)
        meta_path = partial_path.with_suffix(".meta")

        if not partial_path.exists():
            return {"exists": False, "uploaded_size": 0, "total_size": 0}

        uploaded_size = partial_path.stat().st_size
        total_size = 0
        original_name = file_id

        if meta_path.exists():
            try:
                with open(meta_path, "r", encoding="utf-8") as f:
                    meta = json.load(f)
                total_size = meta.get("total_size", 0)
                original_name = meta.get("original_name", file_id)
            except Exception:
                pass

        return {
            "exists": True,
            "uploaded_size": uploaded_size,
            "total_size": total_size,
            "original_name": original_name,
        }

    def cancel_partial_upload(self, file_id: str) -> dict:
        """取消分片上传，清理临时文件"""
        partial_path = self.get_partial_path(file_id)
        meta_path = partial_path.with_suffix(".meta")

        removed = []
        if partial_path.exists():
            partial_path.unlink()
            removed.append(str(partial_path))
        if meta_path.exists():
            meta_path.unlink()
            removed.append(str(meta_path))

        return {"removed": removed}

    # ==================== 缩略图相关 ====================

    def _get_thumbs_dir(self) -> Path:
        """获取缩略图缓存目录"""
        thumbs_dir = self.base_dir / "_thumbs"
        thumbs_dir.mkdir(parents=True, exist_ok=True)
        return thumbs_dir

    def _get_thumb_path(self, original_path: str) -> Path:
        """根据原始文件路径获取缩略图路径"""
        thumb_id = hashlib.md5(original_path.encode("utf-8")).hexdigest()
        return self._get_thumbs_dir() / f"{thumb_id}.jpg"

    def _generate_image_thumbnail(self, full_path: Path, thumb_path: Path, max_width: int = 800) -> dict:
        """Generate thumbnail for image files using PIL"""
        from PIL import Image
        with Image.open(full_path) as img:
            if img.mode in ("RGBA", "P", "LA", "PA"):
                img = img.convert("RGB")
            w, h = img.size
            if w > max_width:
                ratio = max_width / w
                new_w = max_width
                new_h = int(h * ratio)
                img = img.resize((new_w, new_h), Image.LANCZOS)
            else:
                new_w, new_h = w, h
            img.save(thumb_path, "JPEG", quality=85, optimize=True)
        return {"width": new_w, "height": new_h}

    def _generate_video_thumbnail(self, full_path: Path, thumb_path: Path, max_width: int = 800) -> dict:
        """Generate thumbnail for video files by extracting the first frame using imageio"""
        import imageio.v3 as iio
        from PIL import Image
        import numpy as np
        # Extract first frame (frame at index 0)
        frame = iio.imread(full_path, plugin="pyav", index=0)
        # imageio returns HWC numpy array, convert to RGB if needed
        if frame.ndim == 2:
            frame = np.stack([frame] * 3, axis=-1)
        elif frame.shape[-1] == 4:
            frame = frame[:, :, :3]
        img = Image.fromarray(frame)
        w, h = img.size
        if w > max_width:
            ratio = max_width / w
            new_w = max_width
            new_h = int(h * ratio)
            img = img.resize((new_w, new_h), Image.LANCZOS)
        else:
            new_w, new_h = w, h
        img.save(thumb_path, "JPEG", quality=85, optimize=True)
        return {"width": new_w, "height": new_h}

    def generate_thumbnail(self, original_path: str, max_width: int = 800) -> dict:
        """
        生成缩略图（如果已存在则直接返回）
        Returns: {thumb_path, width, height, cached}
        """
        thumb_path = self._get_thumb_path(original_path)

        # 已缓存
        if thumb_path.exists():
            return {
                "thumb_path": str(thumb_path),
                "cached": True,
                "width": None,
                "height": None,
            }

        # 尝试生成缩略图
        full_path = self._to_fs_path(original_path)
        if not full_path.exists():
            raise FileNotFoundError(f"Original file not found: {original_path}")

        ext = Path(original_path).suffix.lower()
        if ext in VIDEO_EXTS:
            result = self._generate_video_thumbnail(full_path, thumb_path, max_width)
            logger.info(f"Video thumbnail generated: {original_path} -> {thumb_path.name}")
        elif ext in THUMB_SUPPORTED_EXTS:
            result = self._generate_image_thumbnail(full_path, thumb_path, max_width)
            logger.info(f"Image thumbnail generated: {original_path} -> {thumb_path.name}")
        else:
            raise ValueError(f"Unsupported thumbnail format: {ext}")

        return {
                "thumb_path": str(thumb_path),
                "cached": False,
                "width": result.get("width"),
                "height": result.get("height"),
            }

    def get_thumb_id(self, original_path: str) -> str:
        """获取缩略图的文件ID（用于 URL）"""
        return hashlib.md5(original_path.encode("utf-8")).hexdigest()

    # ==================== 删除 & 重命名 ====================

    def delete_photos(self, paths: list) -> dict:
        """
        删除指定路径的照片文件及其缩略图，同时从 manifest 中移除记录。

        Args:
            paths: 要删除的文件相对路径列表，如 ["2026/04/26/IMG.jpg", "2026/04/27/VID.mp4"]

        Returns:
            {"deleted": [路径列表], "failed": [{"path": 路径, "reason": 原因}]}
        """
        manifest = self._load_manifest()
        deleted = []
        failed = []

        for rel_path in paths:
            # 1. 找文件并删除
            full_path = self._to_fs_path(rel_path)
            thumb_path = self._get_thumb_path(rel_path)
            try:
                if full_path.exists():
                    full_path.unlink()
                if thumb_path.exists():
                    thumb_path.unlink()
                deleted.append(rel_path)
            except Exception as e:
                failed.append({"path": rel_path, "reason": str(e)})
                continue

            # 2. 从 manifest 中移除（按 saved_path 匹配）
            removed = False
            for hash_key, entries in list(manifest.items()):
                if len(hash_key) != 64:
                    continue
                new_entries = [e for e in entries if e.get("saved_path") != rel_path]
                if len(new_entries) < len(entries):
                    if new_entries:
                        manifest[hash_key] = new_entries
                    else:
                        del manifest[hash_key]
                    removed = True
            if not removed:
                logger.warning(f"File {rel_path} not found in manifest, already deleted?")

        self._save_manifest(manifest)
        logger.info(f"Deleted {len(deleted)} files, {len(failed)} failed")
        return {"deleted": deleted, "failed": failed}

    def rename_photo(self, old_path: str, new_name: str) -> dict:
        """
        重命名照片文件，更新 manifest 和缩略图。

        Args:
            old_path: 原相对路径，如 "2026/04/26/IMG.jpg"
            new_name: 新文件名（不含路径），如 "NEW_NAME.jpg"

        Returns:
            {"old_path": 原路径, "new_path": 新路径, "new_id": 新md5(id)}
        """
        import re as re_module
        old_full = self._to_fs_path(old_path)
        if not old_full.exists():
            raise FileNotFoundError(f"File not found: {old_path}")

        ext = Path(old_full).suffix.lower()
        if not re_module.match(r'^[\w\-\.]+$', new_name):
            raise ValueError("Invalid filename: only alphanumeric, dash, underscore, dot allowed")

        new_name_safe = secure_filename(new_name)
        if Path(new_name_safe).suffix.lower() != ext:
            new_name_safe = new_name_safe + ext

        # 新路径 = 同一目录 + 新文件名
        parent_dir = old_full.parent
        new_full = parent_dir / new_name_safe

        if new_full.exists():
            raise FileExistsError(f"Target file already exists: {new_name_safe}")

        # 重命名磁盘文件
        old_full.rename(new_full)
        logger.info(f"Renamed: {old_full} -> {new_full}")

        # 更新 manifest（按 saved_path 匹配）
        manifest = self._load_manifest()
        updated = False
        for hash_key, entries in list(manifest.items()):
            if len(hash_key) != 64:
                continue
            for i, e in enumerate(entries):
                if e.get("saved_path") == old_path:
                    e["saved_name"] = new_name_safe
                    e["original_name"] = new_name_safe
                    # 更新 saved_path
                    new_rel = str(new_full.relative_to(self.base_dir))
                    e["saved_path"] = new_rel
                    entries[i] = e
                    manifest[hash_key] = entries
                    updated = True
                    logger.info(f"Manifest updated for hash {hash_key[:16]}...")
                    break

        if updated:
            self._save_manifest(manifest)
        else:
            logger.warning(f"Path {old_path} not found in manifest")

        # 删除旧缩略图（基于 old_path 的 md5）
        old_thumb = self._get_thumb_path(old_path)
        if old_thumb.exists():
            old_thumb.unlink()

        # 返回新路径及新 id
        new_rel_path = str(new_full.relative_to(self.base_dir))
        return {
            "old_path": old_path,
            "new_path": new_rel_path,
            "new_id": self.get_thumb_id(new_rel_path)
        }

    def get_gallery_list(self) -> dict:
        """
        获取所有照片列表，按日期分组
        直接扫描文件系统，不依赖 manifest（manifest 的 saved_path 在 WSL 下可能有路径不兼容问题）
        Returns: {groups: [{date, photos: [{id, name, path, size, type}]}]}
        """
        groups = {}  # date -> photos

        # 遍历 base_dir 下的所有年/月/日 子目录
        for year_dir in sorted(self.base_dir.iterdir(), reverse=True):
            if not year_dir.is_dir() or not year_dir.name.isdigit():
                continue
            for month_dir in sorted(year_dir.iterdir(), reverse=True):
                if not month_dir.is_dir() or not month_dir.name.isdigit():
                    continue
                for day_dir in sorted(month_dir.iterdir(), reverse=True):
                    if not day_dir.is_dir() or not day_dir.name.isdigit():
                        continue

                    date_str = f"{year_dir.name}-{month_dir.name}-{day_dir.name}"
                    date_key = f"{year_dir.name}/{month_dir.name}/{day_dir.name}"

                    for photo_file in day_dir.iterdir():
                        # 跳过内部目录
                        if photo_file.is_dir() or photo_file.name in ("_thumbs", "_partial"):
                            continue
                        ext = photo_file.suffix.lower()
                        if ext not in IMAGE_EXTS and ext not in VIDEO_EXTS:
                            continue

                        file_type = "video" if ext in VIDEO_EXTS else "image"
                        try:
                            size = photo_file.stat().st_size
                        except OSError:
                            size = 0

                        photo_entry = {
                            "id": self.get_thumb_id(date_key + "/" + photo_file.name),
                            "name": photo_file.name,
                            "path": f"{date_key}/{photo_file.name}",
                            "size": size,
                            "type": file_type,
                        }

                        if date_str not in groups:
                            groups[date_str] = []
                        groups[date_str].append(photo_entry)

        # 转换为列表并按日期降序排序
        result = []
        for date in sorted(groups.keys(), reverse=True):
            result.append({
                "date": date,
                "photos": groups[date],
            })

        total = sum(len(g["photos"]) for g in result)
        return {"groups": result, "total_count": total}

