"""请求处理模块"""
import logging
from datetime import datetime
from pathlib import Path
from flask import request, jsonify
from werkzeug.utils import secure_filename

from auth import require_auth, verify_user, create_token
from storage import PhotoStorage, SUPPORTED_EXTS, VIDEO_EXTS

logger = logging.getLogger(__name__)


def setup_routes(app, photo_storage: PhotoStorage, config: dict):
    """配置所有路由"""

    # ==================== 认证相关 ====================

    @app.route("/api/login", methods=["POST"])
    def login():
        """用户登录"""
        data = request.get_json()
        if not data:
            return jsonify({"error": "Invalid request body"}), 400

        username = data.get("username", "")
        password = data.get("password", "")

        if not username or not password:
            return jsonify({"error": "Username and password required"}), 400

        if not verify_user(username, password, config):
            logger.warning(f"Failed login attempt for user: {username}")
            return jsonify({"error": "Invalid credentials"}), 401

        token = create_token(username, config)
        logger.info(f"User logged in: {username}")

        return jsonify({
            "token": token,
            "username": username,
            "message": "Login successful"
        })

    auth_required = require_auth(config)

    @app.route("/api/user/info", methods=["GET"])
    @auth_required
    def get_user_info():
        """获取当前用户信息"""
        return jsonify({
            "username": request.user,
            "server_time": datetime.now().isoformat()
        })

    # ==================== 照片同步相关 ====================

    @app.route("/api/sync/status", methods=["GET"])
    @auth_required
    def get_sync_status():
        """获取同步状态"""
        stats = photo_storage.get_storage_stats()
        return jsonify({
            "status": "ready",
            "username": request.user,
            "storage": stats,
            "server_time": datetime.now().isoformat()
        })

    @app.route("/api/upload/photo", methods=["POST"])
    @auth_required
    def upload_photo():
        """上传单张照片/视频"""
        if "file" not in request.files:
            return jsonify({"error": "No file part in request"}), 400

        file = request.files["file"]
        if file.filename == "":
            return jsonify({"error": "No file selected"}), 400

        ext = file.filename.rsplit(".", 1)[-1].lower() if "." in file.filename else ""
        if f".{ext}" not in SUPPORTED_EXTS:
            return jsonify({
                "error": f"Unsupported file type: .{ext}. Supported: {', '.join(SUPPORTED_EXTS)}"
            }), 400

        timestamp = request.form.get("timestamp", type=int)
        device_id = request.form.get("device_id", "unknown")

        original_name = secure_filename(file.filename)
        file_data = file.read()

        try:
            result = photo_storage.save_photo(file_data, original_name, timestamp)
            logger.info(f"File uploaded: {original_name} ({result['type']}) by {request.user} from {device_id}")
            return jsonify({
                "success": True,
                "message": "File uploaded successfully",
                "data": {
                    "original_name": result["original_name"],
                    "saved_path": result["path"],
                    "size": result["size"],
                    "type": result["type"]
                }
            })
        except Exception as e:
            import traceback
            logger.error(f"Failed to save file: {e}\n{traceback.format_exc()}")
            return jsonify({"error": "Failed to save file"}), 500

    @app.route("/api/upload/batch", methods=["POST"])
    @auth_required
    def upload_batch():
        """批量上传照片"""
        if "files" not in request.files:
            return jsonify({"error": "No files part in request"}), 400

        files = request.files.getlist("files")
        if not files:
            return jsonify({"error": "No files selected"}), 400

        device_id = request.form.get("device_id", "unknown")
        results = []
        errors = []

        for file in files:
            if file.filename == "":
                continue
            timestamp = request.form.get(f"timestamp_{file.filename}", type=int)
            original_name = secure_filename(file.filename)
            try:
                result = photo_storage.save_photo(file.read(), original_name, timestamp)
                results.append({
                    "original_name": original_name,
                    "saved_path": result["path"],
                    "size": result["size"],
                    "type": result["type"]
                })
            except Exception as e:
                logger.error(f"Failed to save {original_name}: {e}")
                errors.append({"filename": original_name, "error": str(e)})

        logger.info(f"Batch upload completed: {len(results)} success, {len(errors)} failed by {request.user}")

        return jsonify({
            "success": len(results) > 0,
            "message": f"{len(results)} photos uploaded, {len(errors)} failed",
            "uploaded": results,
            "errors": errors
        })

    @app.route("/api/sync/existing", methods=["GET"])
    @auth_required
    def get_existing_files():
        """获取已存在的文件列表（用于增量同步）"""
        files = photo_storage.list_existing_files()
        return jsonify({"success": True, "count": len(files) - 1, "files": files})

    @app.route("/api/sync/check-by-names", methods=["POST"])
    @auth_required
    def check_files_by_names():
        """基于 name+size 快速检查文件是否存在（不计算哈希，适合首次过滤）

        请求体: { "files": [ { "name": "xxx.jpg", "size": 1234 }, ... ] }
        响应:   { "success": true, "results": { "xxx.jpg_1234": true/false } }
        """
        data = request.get_json()
        if not data or "files" not in data:
            return jsonify({"error": "files list required"}), 400

        files = data["files"]
        # 加载 manifest 并构建内存中的 (name, size) -> hash 反向索引
        manifest = photo_storage._load_manifest()
        name_size_index = {}
        for hash_key, entries in manifest.items():
            if not isinstance(hash_key, str) or len(hash_key) != 64:
                continue
            entry = entries[0] if isinstance(entries, list) and entries else entries
            if not isinstance(entry, dict):
                continue
            original_name = entry.get("original_name", "")
            size = entry.get("size", 0)
            name_size_index[(original_name, size)] = hash_key

        results = {}
        for item in files:
            name = item.get("name", "")
            size = item.get("size", 0)
            key = (name, size)
            results[f"{name}_{size}"] = key in name_size_index

        return jsonify({"success": True, "results": results})

    @app.route("/api/sync/check", methods=["POST"])
    @auth_required
    def check_files():
        """批量检查文件是否已存在（基于内容哈希）

        请求体: { "files": [ { "name": "xxx.jpg", "size": 1234, "hash": "sha256..." }, ... ] }
        响应:   { "success": true, "results": { "sha256...": { "exists": true/false, "server_name": "..." } } }
        """
        data = request.get_json()
        if not data or "files" not in data:
            return jsonify({"error": "files list required"}), 400

        files = data["files"]
        manifest = photo_storage._load_manifest()

        results = {}
        for item in files:
            file_hash = item.get("hash", "")
            name = item.get("name", "")
            size = item.get("size", 0)

            if not file_hash or len(file_hash) != 64:
                # 没有提供有效哈希，尝试用 (name, size) 匹配
                key = (name, size)
                index = manifest.get("__index__", {})
                matched_hash = index.get(key) if isinstance(index, dict) else None
                if matched_hash and matched_hash in manifest:
                    results[file_hash or f"{name}_{size}"] = {
                        "exists": True,
                        "server_name": manifest[matched_hash].get("original_name", name)
                    }
                else:
                    results[file_hash or f"{name}_{size}"] = {"exists": False}
                continue

            # 新格式: manifest[hash] -> dict (单个文件信息，不是列表)
            # 注意: manifest.get(hash, []) 返回空 dict {} 时，if dict 是 truthy！
            #     但旧代码用 entries[0] 期望列表格式，会导致 KeyError
            #     当前实际存储是 dict，直接取值即可
            if file_hash in manifest:
                entry = manifest[file_hash]
                results[file_hash] = {
                    "exists": True,
                    "server_name": entry.get("original_name", "") if isinstance(entry, dict) else ""
                }
            else:
                # hash 不在 manifest，降级用 name+size 查找
                name_size_index = manifest.get("__index__", {})
                key = (name, size)
                matched_hash = name_size_index.get(key) if isinstance(name_size_index, dict) else None
                if matched_hash and matched_hash in manifest:
                    entry = manifest[matched_hash]
                    results[file_hash] = {
                        "exists": True,
                        "server_name": entry.get("original_name", name) if isinstance(entry, dict) else name
                    }
                else:
                    results[file_hash] = {"exists": False}

        return jsonify({"success": True, "results": results})

    # ==================== 分片续传相关 ====================

    @app.route("/api/upload/resume/init", methods=["POST"])
    @auth_required
    def resume_init():
        """初始化分片上传"""
        data = request.get_json()
        if not data:
            return jsonify({"error": "Invalid request"}), 400

        file_id = data.get("file_id")
        total_size = data.get("total_size", 0)
        original_name = data.get("original_name", "")

        if not file_id:
            return jsonify({"error": "file_id required"}), 400

        ext = original_name.rsplit(".", 1)[-1].lower() if "." in original_name else ""
        if f".{ext}" not in SUPPORTED_EXTS:
            return jsonify({"error": f"Unsupported file type: .{ext}"}), 400

        timestamp = data.get("timestamp")
        result = photo_storage.init_partial_upload(file_id, total_size, original_name)
        logger.info(f"Partial upload init: {original_name} ({file_id}), already have {result['uploaded_size']}/{total_size} bytes")

        return jsonify({
            "success": True,
            "uploaded_size": result["uploaded_size"],
            "total_size": total_size,
        })

    @app.route("/api/upload/resume/chunk", methods=["POST"])
    @auth_required
    def resume_chunk():
        """上传分片数据"""
        file_id = request.form.get("file_id")
        if not file_id:
            return jsonify({"error": "file_id required"}), 400

        if "chunk" not in request.files:
            return jsonify({"error": "No chunk part"}), 400

        chunk = request.files["chunk"]
        chunk_data = chunk.read()
        result = photo_storage.append_partial(file_id, chunk_data)

        meta_path = photo_storage.get_partial_path(file_id).with_suffix(".meta")
        total_size = 0
        if meta_path.exists():
            import json as _json
            try:
                with open(meta_path, "r", encoding="utf-8") as f:
                    meta = _json.load(f)
                total_size = meta.get("total_size", 0)
            except Exception:
                pass

        logger.debug(f"Chunk appended for {file_id}: {result['uploaded_size']}/{total_size}")

        return jsonify({
            "success": True,
            "uploaded_size": result["uploaded_size"],
            "total_size": total_size,
        })

    @app.route("/api/upload/resume/complete", methods=["POST"])
    @auth_required
    def resume_complete():
        """完成分片上传"""
        data = request.get_json()
        if not data:
            return jsonify({"error": "Invalid request"}), 400

        file_id = data.get("file_id")
        if not file_id:
            return jsonify({"error": "file_id required"}), 400

        timestamp = data.get("timestamp")
        if timestamp:
            import json as _json
            partial_path = photo_storage.get_partial_path(file_id)
            meta_path = partial_path.with_suffix(".meta")
            if meta_path.exists():
                try:
                    with open(meta_path, "r", encoding="utf-8") as f:
                        meta = _json.load(f)
                    meta["timestamp"] = timestamp
                    with open(meta_path, "w", encoding="utf-8") as f:
                        _json.dump(meta, f, ensure_ascii=False)
                except Exception:
                    pass

        try:
            result = photo_storage.complete_partial_upload(file_id)
            logger.info(f"Partial upload completed: {result['original_name']} by {request.user}")
            return jsonify({"success": True, "data": result})
        except FileNotFoundError as e:
            return jsonify({"error": str(e)}), 404
        except ValueError as e:
            return jsonify({"error": str(e)}), 400

    @app.route("/api/upload/resume/status", methods=["GET"])
    @auth_required
    def resume_status():
        """查询分片上传状态"""
        file_id = request.args.get("file_id")
        if not file_id:
            return jsonify({"error": "file_id required"}), 400

        result = photo_storage.get_partial_status(file_id)
        return jsonify({"success": True, "data": result})

    @app.route("/api/upload/resume/cancel", methods=["POST"])
    @auth_required
    def resume_cancel():
        """取消分片上传"""
        data = request.get_json()
        if not data:
            return jsonify({"error": "Invalid request"}), 400

        file_id = data.get("file_id")
        if not file_id:
            return jsonify({"error": "file_id required"}), 400

        result = photo_storage.cancel_partial_upload(file_id)
        logger.info(f"Partial upload cancelled: {file_id}")
        return jsonify({"success": True, "removed": result["removed"]})

    # ==================== 云相册 ====================

    @app.route("/api/gallery/list", methods=["GET"])
    @auth_required
    def gallery_list():
        """获取所有照片列表，按日期分组"""
        try:
            result = photo_storage.get_gallery_list()
            return jsonify({
                "success": True,
                "groups": result["groups"],
                "total_count": result["total_count"],
            })
        except Exception as e:
            logger.error(f"gallery_list failed: {e}")
            return jsonify({"error": str(e)}), 500

    @app.route("/api/gallery/thumb/<path:thumb_id>", methods=["GET"])
    @auth_required
    def gallery_thumb(thumb_id: str):
        """获取缩略图"""
        normalized = thumb_id.replace("\\", "/")
        try:
            thumb_info = photo_storage.generate_thumbnail(normalized)
            thumb_path = Path(thumb_info["thumb_path"])
            from flask import send_file
            return send_file(thumb_path, mimetype="image/jpeg")
        except FileNotFoundError:
            return jsonify({"error": "Original file not found"}), 404
        except ValueError:
            return jsonify({"error": "No thumbnail for video"}), 404
        except Exception as e:
            logger.error(f"gallery_thumb failed for {thumb_id}: {e}")
            return jsonify({"error": str(e)}), 500

    @app.route("/api/gallery/photo/<path:photo_path>", methods=["GET"])
    @auth_required
    def gallery_photo(photo_path: str):
        """获取原图"""
        full_path = photo_storage.base_dir / photo_path
        if not full_path.exists():
            return jsonify({"error": "Photo not found"}), 404

        ext = Path(photo_path).suffix.lower()
        if ext in VIDEO_EXTS:
            mimetype = "video/mp4"
        elif ext in {".jpg", ".jpeg"}:
            mimetype = "image/jpeg"
        elif ext == ".png":
            mimetype = "image/png"
        elif ext == ".gif":
            mimetype = "image/gif"
        elif ext == ".webp":
            mimetype = "image/webp"
        else:
            mimetype = "application/octet-stream"

        from flask import send_file
        return send_file(full_path, mimetype=mimetype)

    @app.route("/api/gallery/delete", methods=["POST"])
    @auth_required
    def gallery_delete():
        """批量删除照片"""
        data = request.get_json()
        if not data or "paths" not in data:
            return jsonify({"error": "paths required"}), 400
        if not isinstance(data["paths"], list):
            return jsonify({"error": "paths must be a list"}), 400
        try:
            result = photo_storage.delete_photos(data["paths"])
            return jsonify({"success": True, **result})
        except Exception as e:
            logger.error(f"gallery_delete failed: {e}")
            return jsonify({"error": str(e)}), 500

    @app.route("/api/gallery/rename", methods=["POST"])
    @auth_required
    def gallery_rename():
        """重命名照片"""
        data = request.get_json()
        if not data or "path" not in data or "new_name" not in data:
            return jsonify({"error": "path and new_name required"}), 400
        try:
            result = photo_storage.rename_photo(data["path"], data["new_name"])
            return jsonify({"success": True, **result})
        except FileNotFoundError as e:
            return jsonify({"error": str(e)}), 404
        except FileExistsError as e:
            return jsonify({"error": str(e)}), 409
        except ValueError as e:
            return jsonify({"error": str(e)}), 400
        except Exception as e:
            logger.error(f"gallery_rename failed: {e}")
            return jsonify({"error": str(e)}), 500

    # ==================== 健康检查 ====================

    @app.route("/api/health", methods=["GET"])
    def health_check():
        """健康检查"""
        return jsonify({"status": "healthy", "server_time": datetime.now().isoformat()})

    @app.route("/", methods=["GET"])
    def index():
        """首页"""
        return jsonify({
            "service": "LANSync Server",
            "version": "1.0.0",
            "endpoints": [
                "POST /api/login",
                "GET /api/user/info",
                "GET /api/sync/status",
                "POST /api/upload/photo",
                "POST /api/upload/batch",
                "GET /api/health"
            ]
        })
