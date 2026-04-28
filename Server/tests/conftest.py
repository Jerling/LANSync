"""测试夹具"""
import os
import tempfile
import shutil
from pathlib import Path

import pytest


@pytest.fixture(scope="function")
def temp_storage():
    """每个测试用独立的临时存储目录"""
    d = tempfile.mkdtemp(prefix="lansync_test_")
    yield d
    shutil.rmtree(d, ignore_errors=True)


@pytest.fixture(scope="function")
def temp_config(temp_storage):
    """生成临时 config.yaml，指向临时存储目录"""
    import yaml

    config = {
        "server": {"host": "127.0.0.1", "port": 18765, "debug": False},
        "auth": {"jwt_secret": "test-secret-key", "token_expire_hours": 1},
        "storage": {"base_dir": temp_storage, "max_file_size_mb": 100},
        "logging": {"level": "DEBUG", "file": "/tmp/lansync_test.log"},
        "users": [{"username": "testuser", "password": "testpass"}],
    }
    path = f"/tmp/lansync_test_{os.getpid()}.yaml"
    with open(path, "w", encoding="utf-8") as f:
        yaml.dump(config, f)
    yield path
    if os.path.exists(path):
        os.unlink(path)


@pytest.fixture(scope="function")
def app(temp_config):
    """创建测试用 Flask app"""
    import sys
    sys.path.insert(0, str(Path(__file__).parent.parent))

    from app import create_app
    application = create_app(temp_config)
    application.config["TESTING"] = True
    yield application


@pytest.fixture(scope="function")
def client(app):
    """Flask test client"""
    return app.test_client()


@pytest.fixture(scope="function")
def auth_headers(client):
    """登录并返回带 Token 的请求头（使用 temp_config 中的用户）"""
    resp = client.post("/api/login", json={"username": "testuser", "password": "testpass"})
    assert resp.status_code == 200, f"Login failed: {resp.get_json()}"
    token = resp.get_json()["token"]
    return {"Authorization": f"Bearer {token}"}
