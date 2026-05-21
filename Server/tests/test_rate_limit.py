"""测试登录限流功能 - RED 阶段"""
import pytest
import sys
from pathlib import Path

# 添加项目路径
sys.path.insert(0, str(Path(__file__).parent.parent))

from app import create_app


@pytest.fixture
def client():
    """创建测试客户端"""
    app = create_app()
    app.config["TESTING"] = True
    with app.test_client() as client:
        yield client


def test_login_rate_limited(client):
    """测试同一 IP 5分钟内登录超过5次被限流"""
    # 使用无效凭据尝试登录6次
    for i in range(6):
        response = client.post("/api/login", json={
            "username": "testuser",
            "password": "wrongpassword"
        })
        # 前5次应该返回 401 (无效凭据)
        if i < 5:
            assert response.status_code == 401, f"Attempt {i+1} should return 401"
        else:
            # 第6次应该被限流，返回 429
            assert response.status_code == 429, f"Attempt {i+1} should return 429 (rate limited)"


def test_rate_limit_returns_429(client):
    """测试超限后返回 429 状态码"""
    # 先触发限流
    for i in range(6):
        response = client.post("/api/login", json={
            "username": "anotheruser",
            "password": "wrongpassword"
        })
    
    # 第6次请求应该返回 429
    assert response.status_code == 429
    # 响应应该包含限流相关信息
    data = response.get_json()
    assert data is not None
    # Flask-Limiter 默认会返回 429 和错误消息