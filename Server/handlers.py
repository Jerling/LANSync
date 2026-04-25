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
