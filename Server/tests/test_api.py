"""Server API 功能测试"""
import io
import os
import json
import tempfile
from pathlib import Path
from urllib.parse import urlencode

import pytest
from werkzeug.datastructures import MultiDict


def make_multipart(data: dict) -> MultiDict:
    """构造 multipart/form-data，文件用 (file_obj, filename, mimetype) 元组"""
    md = MultiDict()
    for key, value in data.items():
        if isinstance(value, tuple):
            md.add(key, value)
        else:
            md.add(key, str(value))
    return md


class TestAuth:
    def test_login_success(self, client):
        resp = client.post("/api/login", json={"username": "testuser", "password": "testpass"})
        assert resp.status_code == 200
        data = resp.get_json()
        assert data["token"]
        assert data["username"] == "testuser"
        assert data["message"] == "Login successful"

    def test_login_wrong_password(self, client):
        resp = client.post("/api/login", json={"username": "testuser", "password": "wrongpass"})
        assert resp.status_code == 401

    def test_login_missing_fields(self, client):
        resp = client.post("/api/login", json={"username": "testuser"})
        assert resp.status_code == 400

    def test_login_no_body(self, client):
        resp = client.post("/api/login")
        assert resp.status_code == 415  # Flask 对无 Content-Type 的 JSON 返回 415

    def test_user_info_requires_auth(self, client):
        resp = client.get("/api/user/info")
        assert resp.status_code == 401

    def test_user_info_success(self, client, auth_headers):
        resp = client.get("/api/user/info", headers=auth_headers)
        assert resp.status_code == 200
        assert resp.get_json()["username"] == "testuser"

    def test_user_info_invalid_token(self, client):
        resp = client.get("/api/user/info", headers={"Authorization": "Bearer invalid"})
        assert resp.status_code == 401


class TestHealth:
    def test_health_check(self, client):
        resp = client.get("/api/health")
        assert resp.status_code == 200
        assert resp.get_json()["status"] == "healthy"

    def test_index(self, client):
        resp = client.get("/")
        assert resp.status_code == 200
        assert resp.content_type == "text/html; charset=utf-8"


class TestSyncStatus:
    def test_sync_status_requires_auth(self, client):
        resp = client.get("/api/sync/status")
        assert resp.status_code == 401

    def test_sync_status_success(self, client, auth_headers):
        resp = client.get("/api/sync/status", headers=auth_headers)
        assert resp.status_code == 200
        data = resp.get_json()
        assert data["status"] == "ready"
        assert data["username"] == "testuser"
        assert "storage" in data


class TestUploadPhoto:
    def test_upload_photo_requires_auth(self, client):
        resp = client.post("/api/upload/photo")
        assert resp.status_code == 401

    def test_upload_photo_no_file(self, client, auth_headers):
        resp = client.post("/api/upload/photo", headers=auth_headers)
        assert resp.status_code == 400

    def test_upload_unsupported_type(self, client, auth_headers):
        data = make_multipart({
            "file": (io.BytesIO(b"hello"), "test.txt", "text/plain"),
            "timestamp": "0",
            "device_id": "test",
        })
        resp = client.post("/api/upload/photo", headers=auth_headers, data=data)
        assert resp.status_code == 400
        assert "Unsupported" in resp.get_json()["error"]

    def test_upload_single_photo(self, client, auth_headers):
        data = make_multipart({
            "file": (io.BytesIO(b"\xff\xd8\xff\xe0test image"), "IMG_20260427_123456.jpg", "image/jpeg"),
            "timestamp": "0",
            "device_id": "test",
        })
        resp = client.post("/api/upload/photo", headers=auth_headers, data=data)
        assert resp.status_code == 200
        d = resp.get_json()
        assert d["success"] is True
        assert d["data"]["type"] == "image"
        assert "saved_path" in d["data"]

    def test_upload_single_video(self, client, auth_headers):
        data = make_multipart({
            "file": (io.BytesIO(b"fake video content"), "VID_20260427_654321.mp4", "video/mp4"),
            "timestamp": "0",
            "device_id": "test",
        })
        resp = client.post("/api/upload/photo", headers=auth_headers, data=data)
        assert resp.status_code == 200
        d = resp.get_json()
        assert d["success"] is True
        assert d["data"]["type"] == "video"


class TestUploadBatch:
    def test_upload_batch_requires_auth(self, client):
        resp = client.post("/api/upload/batch")
        assert resp.status_code == 401

    def test_upload_batch_success(self, client, auth_headers):
        data = MultiDict()
        data.add("files", (io.BytesIO(b"img1"), "IMG_001.jpg", "image/jpeg"))
        data.add("files", (io.BytesIO(b"img2"), "IMG_002.jpg", "image/jpeg"))
        data.add("device_id", "test")
        resp = client.post("/api/upload/batch", headers=auth_headers, data=data)
        assert resp.status_code == 200
        d = resp.get_json()
        assert d["success"] is True
        assert len(d["uploaded"]) == 2


class TestSyncExisting:
    def test_sync_existing_requires_auth(self, client):
        resp = client.get("/api/sync/existing")
        assert resp.status_code == 401

    def test_sync_existing_success(self, client, auth_headers):
        resp = client.get("/api/sync/existing", headers=auth_headers)
        assert resp.status_code == 200
        data = resp.get_json()
        assert data["success"] is True
        assert "files" in data


class TestCheckFiles:
    def test_check_files_requires_auth(self, client):
        resp = client.post("/api/sync/check", json={"files": []})
        assert resp.status_code == 401

    def test_check_files_empty_list(self, client, auth_headers):
        resp = client.post("/api/sync/check", headers=auth_headers, json={"files": []})
        assert resp.status_code == 200
        data = resp.get_json()
        assert data["success"] is True
        assert data["results"] == {}

    def test_check_files_not_found(self, client, auth_headers):
        resp = client.post(
            "/api/sync/check",
            headers=auth_headers,
            json={"files": [{"hash": "abc123" + "0" * 53, "name": "test.jpg", "size": 100}]},
        )
        assert resp.status_code == 200
        data = resp.get_json()
        assert data["results"]["abc123" + "0" * 53]["exists"] is False

    def test_check_files_by_names_requires_auth(self, client):
        resp = client.post("/api/sync/check-by-names", json={"files": []})
        assert resp.status_code == 401

    def test_check_files_by_names_empty_list(self, client, auth_headers):
        resp = client.post("/api/sync/check-by-names", headers=auth_headers, json={"files": []})
        assert resp.status_code == 200
        data = resp.get_json()
        assert data["success"] is True
        assert data["results"] == {}

    def test_check_files_by_names_not_found(self, client, auth_headers):
        resp = client.post(
            "/api/sync/check-by-names",
            headers=auth_headers,
            json={"files": [{"name": "nonexist.jpg", "size": 100}]},
        )
        assert resp.status_code == 200
        data = resp.get_json()
        assert data["results"]["nonexist.jpg_100"] is False


class TestResumeUpload:
    def test_resume_init_requires_auth(self, client):
        resp = client.post("/api/upload/resume/init", json={"file_id": "abc", "total_size": 100, "original_name": "test.jpg"})
        assert resp.status_code == 401

    def test_resume_init_success(self, client, auth_headers):
        resp = client.post(
            "/api/upload/resume/init",
            headers=auth_headers,
            json={"file_id": "testfile123", "total_size": 1024, "original_name": "IMG_test.jpg"},
        )
        assert resp.status_code == 200
        data = resp.get_json()
        assert data["success"] is True
        assert data["uploaded_size"] == 0

    def test_resume_init_unsupported_type(self, client, auth_headers):
        resp = client.post(
            "/api/upload/resume/init",
            headers=auth_headers,
            json={"file_id": "abc", "total_size": 100, "original_name": "test.txt"},
        )
        assert resp.status_code == 400

    def test_resume_chunk_requires_auth(self, client):
        resp = client.post("/api/upload/resume/chunk")
        assert resp.status_code == 401

    def test_resume_chunk_success(self, client, auth_headers):
        # 先初始化
        client.post(
            "/api/upload/resume/init",
            headers=auth_headers,
            json={"file_id": "chunktest", "total_size": 1024, "original_name": "IMG_chunk.jpg"},
        )
        data = MultiDict()
        data.add("file_id", "chunktest")
        data.add("chunk", (io.BytesIO(b"x" * 512), "chunk1", "application/octet-stream"))
        resp = client.post("/api/upload/resume/chunk", headers=auth_headers, data=data)
        assert resp.status_code == 200
        assert resp.get_json()["success"] is True

    def test_resume_status_requires_auth(self, client):
        resp = client.get("/api/upload/resume/status", query_string={"file_id": "abc"})
        assert resp.status_code == 401

    def test_resume_status_not_found(self, client, auth_headers):
        resp = client.get("/api/upload/resume/status", headers=auth_headers, query_string={"file_id": "notexist"})
        assert resp.status_code == 200
        data = resp.get_json()
        assert data["success"] is True
        assert data["data"]["exists"] is False

    def test_resume_status_found(self, client, auth_headers):
        # 先初始化
        client.post(
            "/api/upload/resume/init",
            headers=auth_headers,
            json={"file_id": "statustest", "total_size": 200, "original_name": "IMG_status.jpg"},
        )
        resp = client.get("/api/upload/resume/status", headers=auth_headers, query_string={"file_id": "statustest"})
        assert resp.status_code == 200
        data = resp.get_json()
        assert data["success"] is True
        assert data["data"]["exists"] is True

    def test_resume_complete_requires_auth(self, client):
        resp = client.post("/api/upload/resume/complete", json={"file_id": "abc"})
        assert resp.status_code == 401

    def test_resume_complete_not_found(self, client, auth_headers):
        resp = client.post(
            "/api/upload/resume/complete",
            headers=auth_headers,
            json={"file_id": "notexist"},
        )
        assert resp.status_code == 404

    def test_resume_cancel_requires_auth(self, client):
        resp = client.post("/api/upload/resume/cancel", json={"file_id": "abc"})
        assert resp.status_code == 401

    def test_resume_cancel_success(self, client, auth_headers):
        # 先初始化
        client.post(
            "/api/upload/resume/init",
            headers=auth_headers,
            json={"file_id": "canceltest", "total_size": 100, "original_name": "IMG_cancel.jpg"},
        )
        resp = client.post(
            "/api/upload/resume/cancel",
            headers=auth_headers,
            json={"file_id": "canceltest"},
        )
        assert resp.status_code == 200
        assert resp.get_json()["success"] is True


class TestGallery:
    def test_gallery_list_requires_auth(self, client):
        resp = client.get("/api/gallery/list")
        assert resp.status_code == 401

    def test_gallery_list_empty(self, client, auth_headers):
        resp = client.get("/api/gallery/list", headers=auth_headers)
        assert resp.status_code == 200
        data = resp.get_json()
        assert data["success"] is True
        assert data["total_count"] == 0
        assert data["groups"] == []

    def test_gallery_thumb_requires_auth(self, client):
        resp = client.get("/api/gallery/thumb/2026/04/27/test.jpg")
        assert resp.status_code == 401

    def test_gallery_thumb_not_found(self, client, auth_headers):
        resp = client.get("/api/gallery/thumb/2026/04/27/nonexist.jpg", headers=auth_headers)
        assert resp.status_code == 404

    def test_gallery_photo_requires_auth(self, client):
        resp = client.get("/api/gallery/photo/2026/04/27/test.jpg")
        assert resp.status_code == 401

    def test_gallery_delete_requires_auth(self, client):
        resp = client.post("/api/gallery/delete", json={"paths": []})
        assert resp.status_code == 401

    def test_gallery_delete_empty_paths(self, client, auth_headers):
        resp = client.post("/api/gallery/delete", headers=auth_headers, json={"paths": []})
        assert resp.status_code == 200
        data = resp.get_json()
        assert data["success"] is True

    def test_gallery_delete_invalid_paths_type(self, client, auth_headers):
        resp = client.post("/api/gallery/delete", headers=auth_headers, json={"paths": "not a list"})
        assert resp.status_code == 400

    def test_gallery_rename_requires_auth(self, client):
        resp = client.post("/api/gallery/rename", json={"path": "a.jpg", "new_name": "b.jpg"})
        assert resp.status_code == 401

    def test_gallery_rename_missing_fields(self, client, auth_headers):
        resp = client.post("/api/gallery/rename", headers=auth_headers, json={"path": "a.jpg"})
        assert resp.status_code == 400

    def test_gallery_rename_not_found(self, client, auth_headers):
        resp = client.post(
            "/api/gallery/rename",
            headers=auth_headers,
            json={"path": "nonexist.jpg", "new_name": "new_name.jpg"},
        )
        assert resp.status_code == 404
