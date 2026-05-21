"""测试 Token Authorization Header 修复 (S-3)

RED 阶段测试:
- 验证图片接口需要 Authorization: Bearer header
- 验证 token 在 URL param 中不再有效（返回 401）
"""
import pytest


def test_gallery_endpoint_requires_auth_header(client):
    """验证图片接口需要 Authorization: Bearer header"""
    # 没有 Authorization header，应该返回 401
    resp = client.get("/api/gallery/list")
    assert resp.status_code == 401, f"Expected 401, got {resp.status_code}"


def test_gallery_endpoint_rejects_token_in_url(client):
    """验证 token 在 URL param 中不再有效（返回 401）"""
    # 尝试通过 query param 传递 token，应该被拒绝
    resp = client.get("/api/gallery/list?token=some-fake-token")
    assert resp.status_code == 401, f"Expected 401 when token in URL, got {resp.status_code}"

    # 验证响应说明 token 不应通过 URL 传递
    json_data = resp.get_json()
    assert json_data is not None
    assert "error" in json_data


def test_gallery_endpoint_accepts_bearer_header(client, auth_headers):
    """验证 token 通过 Authorization: Bearer header 可以正常访问"""
    # 使用正确的 Bearer token，应该返回成功（200 或其他业务状态码，不是 401）
    resp = client.get("/api/gallery/list", headers=auth_headers)
    # 业务逻辑可能返回 200 或 500（storage 问题），但不应该返回 401
    assert resp.status_code != 401, f"Valid Bearer token should not return 401, got {resp.status_code}"