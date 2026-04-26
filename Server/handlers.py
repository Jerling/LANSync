"""请求处理模块"""
import os
import logging
from datetime import datetime
from flask import request, jsonify
from werkzeug.utils import secure_filename

from auth import require_auth, verify_user, create_token
from storage import PhotoStorage, SUPPORTED_EXTS

logger = logging.getLogger(__name__)

def setup_routes(app, photo_storage: PhotoStorage):
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
        
        if not verify_user(username, password):
            logger.warning(f"Failed login attempt for user: {username}")
            return jsonify({"error": "Invalid credentials"}), 401
        
        token = create_token(username)
        logger.info(f"User logged in: {username}")
        
        return jsonify({
            "token": token,
            "username": username,
            "message": "Login successful"
        })
    
    @app.route("/api/user/info", methods=["GET"])
    @require_auth
    def get_user_info():
        """获取当前用户信息"""
        return jsonify({
            "username": request.user,
            "server_time": datetime.now().isoformat()
        })
    
    # ==================== 照片同步相关 ====================
    
    @app.route("/api/sync/status", methods=["GET"])
    @require_auth
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
    @require_auth
    def upload_photo():
        """上传单张照片/视频"""
        if "file" not in request.files:
            return jsonify({"error": "No file part in request"}), 400

        file = request.files["file"]
        if file.filename == "":
            return jsonify({"error": "No file selected"}), 400

        # 验证文件类型
        ext = file.filename.rsplit(".", 1)[-1].lower() if "." in file.filename else ""
        if f".{ext}" not in SUPPORTED_EXTS:
            return jsonify({"error": f"Unsupported file type: .{ext}. Supported: {', '.join(SUPPORTED_EXTS)}"}), 400

        # 获取可选的时间戳（手机照片的拍摄时间）
        timestamp = request.form.get("timestamp", type=int)
        device_id = request.form.get("device_id", "unknown")

        original_name = secure_filename(file.filename)
        file_data = file.read()

        try:
            result = photo_storage.save_photo(
                file_data,
                original_name,
                timestamp
            )

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
    @require_auth
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
                result = photo_storage.save_photo(
                    file.read(),
                    original_name,
                    timestamp
                )
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
    @require_auth
    def get_existing_files():
        """获取已存在的文件列表（用于增量同步）"""
        files = photo_storage.list_existing_files()
        return jsonify({
            "success": True,
            "count": len(files),
            "files": files
        })

    # ==================== 分片续传相关 ====================

    @app.route("/api/upload/resume/init", methods=["POST"])
    @require_auth
    def resume_init():
        """
        初始化分片上传
        请求体: { file_id, total_size, original_name }
        """
        data = request.get_json()
        if not data:
            return jsonify({"error": "Invalid request"}), 400

        file_id = data.get("file_id")
        total_size = data.get("total_size", 0)
        original_name = data.get("original_name", "")

        if not file_id:
            return jsonify({"error": "file_id required"}), 400

        # 验证文件类型
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
    @require_auth
    def resume_chunk():
        """
        上传分片数据
        请求体: { file_id, chunk (bytes in multipart) }
        """
        file_id = request.form.get("file_id")
        if not file_id:
            return jsonify({"error": "file_id required"}), 400

        if "chunk" not in request.files:
            return jsonify({"error": "No chunk part"}), 400

        chunk = request.files["chunk"]
        chunk_data = chunk.read()

        result = photo_storage.append_partial(file_id, chunk_data)

        # 获取 meta 确认进度
        meta_path = photo_storage.get_partial_path(file_id).with_suffix(".meta")
        total_size = 0
        if meta_path.exists():
            try:
                with open(meta_path, "r", encoding="utf-8") as f:
                    meta = json.load(f)
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
    @require_auth
    def resume_complete():
        """
        完成分片上传
        请求体: { file_id, timestamp (optional) }
        """
        data = request.get_json()
        if not data:
            return jsonify({"error": "Invalid request"}), 400

        file_id = data.get("file_id")
        if not file_id:
            return jsonify({"error": "file_id required"}), 400

        # 可选：允许在完成时补充 timestamp
        timestamp = data.get("timestamp")
        if timestamp:
            partial_path = photo_storage.get_partial_path(file_id)
            meta_path = partial_path.with_suffix(".meta")
            if meta_path.exists():
                try:
                    with open(meta_path, "r", encoding="utf-8") as f:
                        meta = json.load(f)
                    meta["timestamp"] = timestamp
                    with open(meta_path, "w", encoding="utf-8") as f:
                        json.dump(meta, f, ensure_ascii=False)
                except Exception:
                    pass

        try:
            result = photo_storage.complete_partial_upload(file_id)
            logger.info(f"Partial upload completed: {result['original_name']} by {request.user}")
            return jsonify({
                "success": True,
                "data": result
            })
        except FileNotFoundError as e:
            return jsonify({"error": str(e)}), 404
        except ValueError as e:
            return jsonify({"error": str(e)}), 400

    @app.route("/api/upload/resume/status", methods=["GET"])
    @require_auth
    def resume_status():
        """
        查询分片上传状态
        参数: file_id
        """
        file_id = request.args.get("file_id")
        if not file_id:
            return jsonify({"error": "file_id required"}), 400

        result = photo_storage.get_partial_status(file_id)
        return jsonify({
            "success": True,
            "data": result
        })

    @app.route("/api/upload/resume/cancel", methods=["POST"])
    @require_auth
    def resume_cancel():
        """
        取消分片上传
        请求体: { file_id }
        """
        data = request.get_json()
        if not data:
            return jsonify({"error": "Invalid request"}), 400

        file_id = data.get("file_id")
        if not file_id:
            return jsonify({"error": "file_id required"}), 400

        result = photo_storage.cancel_partial_upload(file_id)
        logger.info(f"Partial upload cancelled: {file_id}")
        return jsonify({
            "success": True,
            "removed": result["removed"]
        })

    # ==================== 健康检查 ====================
    
    @app.route("/api/health", methods=["GET"])
    def health_check():
        """健康检查"""
        return jsonify({
            "status": "healthy",
            "server_time": datetime.now().isoformat()
        })
    
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
