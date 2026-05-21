"""测试 delete_photos 清理 __index__ + 检测 unlink 静默失败"""
import os
import tempfile
import shutil
import pytest
from pathlib import Path
from unittest.mock import patch, MagicMock


class TestDeleteCleanup:
    """验证 delete_photos 的清理逻辑"""

    def test_delete_removes_from_index(self, temp_storage):
        """验证删除后 __index__ 中 name+size 映射被清理"""
        import sys
        sys.path.insert(0, str(Path(__file__).parent.parent))
        from storage import PhotoStorage

        PhotoStorage.clear_cache()
        storage = PhotoStorage.get_instance(temp_storage, "testuser")

        # 保存一个文件
        test_content = b"TEST_IMAGE_DATA"
        result = storage.save_photo(test_content, "IMG_20240115.jpg", timestamp=1704067200)
        file_hash = result["hash"]

        # 验证 list_existing_files 返回的 __index__ 包含该映射
        files = storage.list_existing_files()
        index = files["__index__"]
        assert ("IMG_20240115.jpg", len(test_content)) in index, \
            f"Expected (name, size) in index, got {index}"
        assert index[("IMG_20240115.jpg", len(test_content))] == file_hash

        # 删除该文件
        rel_path = result["path"]
        delete_result = storage.delete_photos([rel_path])

        assert delete_result["deleted"] == [rel_path], f"Expected deleted={[rel_path]}, got {delete_result}"
        assert delete_result["failed"] == [], f"Expected no failures, got {delete_result['failed']}"

        # 验证 __index__ 中不再有该映射
        files = storage.list_existing_files()
        index = files["__index__"]
        assert ("IMG_20240115.jpg", len(test_content)) not in index, \
            f"Expected (name, size) NOT in index after delete, got {index}"

    def test_delete_returns_failed_on_unlink_error(self, temp_storage):
        """验证 unlink 失败时返回 failed 而非 success"""
        import sys
        sys.path.insert(0, str(Path(__file__).parent.parent))
        from storage import PhotoStorage

        PhotoStorage.clear_cache()
        storage = PhotoStorage.get_instance(temp_storage, "testuser")

        # 保存一个文件
        test_content = b"TEST_IMAGE_DATA"
        result = storage.save_photo(test_content, "IMG_20240115.jpg", timestamp=1704067200)
        rel_path = result["path"]

        # 模拟 unlink 失败：文件存在但无法删除
        full_path = storage._to_fs_path(rel_path)
        assert full_path.exists(), "File should exist before delete"

        # Mock Path.unlink to raise PermissionError
        original_unlink = Path.unlink
        def mock_unlink(self):
            if str(self) == str(full_path):
                raise PermissionError("Permission denied")
            return original_unlink(self)

        with patch.object(Path, 'unlink', mock_unlink):
            delete_result = storage.delete_photos([rel_path])

        # 即使 unlink 失败，也应该清理 manifest/index
        # 但返回结果中该文件应该在 failed 中
        # 关键：deleted 为空说明没有静默失败
        assert rel_path not in delete_result["deleted"], \
            f"Expected {rel_path} NOT in deleted, but got: {delete_result['deleted']}"
        # failed 列表应该包含该路径
        failed_paths = [f["path"] for f in delete_result["failed"]]
        assert rel_path in failed_paths, \
            f"Expected {rel_path} in failed, but got: {delete_result['failed']}"

        # 清理 mock
        Path.unlink = original_unlink

    def test_delete_after_partial_upload(self, temp_storage):
        """验证分片上传残留文件也能被正确删除"""
        import sys
        sys.path.insert(0, str(Path(__file__).parent.parent))
        from storage import PhotoStorage

        PhotoStorage.clear_cache()
        storage = PhotoStorage.get_instance(temp_storage, "testuser")

        # 模拟分片上传的残留文件（直接写入 _partial 目录）
        partial_dir = storage._get_partial_dir()
        file_id = "test_partial_001"
        partial_path = storage.get_partial_path(file_id)
        meta_path = partial_path.with_suffix(".meta")

        # 创建残留文件
        partial_path.write_bytes(b"PARTIAL_DATA_CONTENTS")
        meta = {
            "file_id": file_id,
            "original_name": "RESIDUAL_FILE.jpg",
            "total_size": 1000,
            "uploaded_size": 500,
        }
        meta_path.write_text('{"file_id": "test_partial_001", "original_name": "RESIDUAL_FILE.jpg", "total_size": 1000, "uploaded_size": 500}')

        assert partial_path.exists(), "Partial file should exist before delete"
        assert meta_path.exists(), "Meta file should exist before delete"

        # 删除分片残留
        delete_result = storage.delete_photos([f"_partial/{file_id}.part"])

        # 验证文件被清理
        assert not partial_path.exists(), "Partial file should be deleted"
        assert not meta_path.exists(), "Meta file should be deleted"

        # 验证返回结果
        assert "_partial/test_partial_001.part" in delete_result["deleted"] or \
               delete_result["failed"] == [], \
            f"Expected cleanup success, got {delete_result}"

    def test_delete_verifies_file_removed(self, temp_storage):
        """验证删除后确认文件确实被删除，文件仍存在时加入 failed"""
        import sys
        sys.path.insert(0, str(Path(__file__).parent.parent))
        from storage import PhotoStorage

        PhotoStorage.clear_cache()
        storage = PhotoStorage.get_instance(temp_storage, "testuser")

        # 保存一个文件
        test_content = b"TEST_IMAGE_DATA"
        result = storage.save_photo(test_content, "IMG_20240115.jpg", timestamp=1704067200)
        rel_path = result["path"]

        # Mock unlink to NOT actually delete (simulate silent failure)
        full_path = storage._to_fs_path(rel_path)
        original_unlink = Path.unlink

        call_count = [0]
        def mock_unlink_no_delete(self):
            if str(self) == str(full_path):
                call_count[0] += 1
                # 什么都不做，文件依然存在
                return
            return original_unlink(self)

        with patch.object(Path, 'unlink', mock_unlink_no_delete):
            delete_result = storage.delete_photos([rel_path])

        # 文件实际上还存在（mock 没删），但 unlink 没抛异常
        # 现有代码会在没有异常的情况下把路径加入 deleted
        # 修复后应该检测到文件还在，加入 failed
        assert full_path.exists(), "File should still exist after mock unlink"
        # 修复后：即使没抛异常，也会验证文件是否真的被删除
        failed_paths = [f["path"] for f in delete_result["failed"]]
        # 如果实现正确检测到文件还在，应该在 failed 中
        assert rel_path in failed_paths or len(delete_result["deleted"]) == 0, \
            f"Expected file still exists to be detected, got: {delete_result}"

        # 清理
        Path.unlink = original_unlink
