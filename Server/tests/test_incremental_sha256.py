"""测试分片上传增量SHA256计算"""
import hashlib
import json
import os
import tempfile
import shutil
from pathlib import Path

import pytest

# 使用与 conftest 相同的方式导入
import sys
sys.path.insert(0, str(Path(__file__).parent.parent))


class TestIncrementalSha256:
    """测试增量SHA256计算"""

    def test_partial_upload_recomputes_sha_incrementally(self, temp_storage, temp_config):
        """验证分片时增量算SHA256 - 每个分片写入时都应该更新SHA状态"""
        from storage import PhotoStorage

        storage = PhotoStorage(temp_storage, "testuser")
        file_id = "test_incremental_001"
        original_name = "test_file.jpg"

        # 预先计算完整内容的 SHA256 作为对照
        full_content = b"Hello, World! This is test content."
        expected_sha = hashlib.sha256(full_content).hexdigest()

        # 初始化分片上传 (file_id, total_size, original_name)
        storage.init_partial_upload(file_id, len(full_content), original_name)

        # 模拟分片1: "Hello, "
        chunk1 = b"Hello, "
        storage.append_partial(file_id, chunk1)

        # 读取 meta 文件，验证 sha_state 存在且是中间状态
        partial_path = storage.get_partial_path(file_id)
        meta_path = partial_path.with_suffix(".meta")
        with open(meta_path, "r", encoding="utf-8") as f:
            meta = json.load(f)

        # 第一个分片后应该有 sha_state
        assert "sha_state" in meta, "append_partial 后 meta 文件应保存 sha_state"
        assert "sha_hex" in meta["sha_state"], "sha_state 应包含 sha_hex"
        assert meta["sha_state"]["sha_hex"] != expected_sha, "中间 sha_hex 不应等于最终 sha"

        # 模拟分片2: "World! This is test content."
        chunk2 = b"World! This is test content."
        storage.append_partial(file_id, chunk2)

        # 读取 meta 文件，验证最终 sha_state
        with open(meta_path, "r", encoding="utf-8") as f:
            meta = json.load(f)

        # 所有分片追加后，sha_hex 应该等于最终 SHA256
        assert meta["sha_state"]["sha_hex"] == expected_sha, \
            f"最终 sha_hex 应为 {expected_sha}，实际为 {meta['sha_state']['sha_hex']}"

    def test_complete_upload_uses_cached_sha(self, temp_storage, temp_config):
        """验证完成时直接用缓存的SHA - 不应重新读取文件"""
        from storage import PhotoStorage

        storage = PhotoStorage(temp_storage, "testuser")
        file_id = "test_complete_001"
        original_name = "complete_test.jpg"

        full_content = b"Complete file content for testing."
        expected_sha = hashlib.sha256(full_content).hexdigest()

        # 初始化并完成分片上传 (file_id, total_size, original_name)
        storage.init_partial_upload(file_id, len(full_content), original_name)
        storage.append_partial(file_id, full_content[:10])
        storage.append_partial(file_id, full_content[10:])

        # 验证 meta 中有 sha_state
        partial_path = storage.get_partial_path(file_id)
        meta_path = partial_path.with_suffix(".meta")
        with open(meta_path, "r", encoding="utf-8") as f:
            meta_before = json.load(f)
        assert "sha_state" in meta_before
        assert meta_before["sha_state"]["sha_hex"] == expected_sha

        # 完成上传 - 这里不应该重新读取文件
        result = storage.complete_partial_upload(file_id)

        # 验证返回的 hash 正确
        assert result["hash"] == expected_sha, \
            f"complete_partial_upload 应使用缓存的 SHA256，实际: {result['hash']}"

    def test_meta_file_contains_sha_state(self, temp_storage, temp_config):
        """验证.meta文件存了SHA状态"""
        from storage import PhotoStorage

        storage = PhotoStorage(temp_storage, "testuser")
        file_id = "test_meta_sha_001"
        original_name = "meta_test.jpg"

        content = b"X" * 100  # 100 字节内容
        expected_sha = hashlib.sha256(content).hexdigest()

        # 初始化分片上传
        storage.init_partial_upload(file_id, len(content), original_name)

        # 追加数据
        storage.append_partial(file_id, content)

        # 读取 .meta 文件
        partial_path = storage.get_partial_path(file_id)
        meta_path = partial_path.with_suffix(".meta")
        with open(meta_path, "r", encoding="utf-8") as f:
            meta = json.load(f)

        # 验证 SHA 状态字段
        assert "sha_state" in meta, ".meta 文件应包含 sha_state"
        assert "sha_hex" in meta["sha_state"], "sha_state 应包含 sha_hex"
        assert meta["sha_state"]["sha_hex"] == expected_sha, \
            f"sha_hex 应为 {expected_sha}，实际为 {meta['sha_state']['sha_hex']}"

        # 验证 complete 时删除 meta 文件后仍能返回正确 hash
        result = storage.complete_partial_upload(file_id)
        assert result["hash"] == expected_sha