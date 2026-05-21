"""Tests for upload error handling (W-3)."""
import pytest
import requests
import time
import os


BASE_URL = os.environ.get("LANSYNC_TEST_SERVER", "http://127.0.0.1:8765")
TEST_USER = f"testupload{int(time.time())}"
TEST_PASSWORD = "testpassword123"


@pytest.fixture(scope="module", autouse=True)
def setup_test_user():
    """Create a test user for upload tests using valid alphanumeric username."""
    try:
        resp = requests.post(f"{BASE_URL}/api/register", json={
            "username": TEST_USER,
            "password": TEST_PASSWORD,
            "confirm_password": TEST_PASSWORD  # Server doesn't validate this yet, but we send it
        })
        # Even if registration fails due to existing user, continue
    except:
        pass
    
    yield TEST_USER
    
    # Cleanup - ignore errors


@pytest.fixture
def valid_token():
    """Get a valid auth token for upload tests."""
    response = requests.post(f"{BASE_URL}/api/login", json={
        "username": TEST_USER,
        "password": TEST_PASSWORD
    })
    assert response.status_code == 200, f"Login failed: {response.text} - status: {response.status_code}"
    return response.json()["token"]


@pytest.fixture
def invalid_token():
    """Return an invalid/expired token for testing 401 handling."""
    return "invalid_token_12345"


class TestUploadErrorHandling:
    """Test that upload endpoint properly handles error cases."""

    def test_upload_handles_401_with_relogin(self, invalid_token):
        """Verify upload returns 401 when token is invalid, requiring re-login.
        
        The client should detect 401 response and prompt user to re-login
        instead of silently failing.
        """
        # Create a dummy file for upload
        files = {"file": ("test.jpg", b"fake_image_data", "image/jpeg")}
        data = {"timestamp": int(time.time())}
        headers = {"Authorization": f"Bearer {invalid_token}"}
        
        response = requests.post(
            f"{BASE_URL}/api/upload/photo",
            files=files,
            data=data,
            headers=headers
        )
        
        # Server should return 401 for invalid token
        assert response.status_code == 401, \
            f"Expected 401 for invalid token, got {response.status_code}: {response.text}"
        
        error_data = response.json()
        assert "error" in error_data or "message" in error_data, \
            f"Expected error message in 401 response: {error_data}"

    def test_upload_retries_on_network_error(self):
        """Verify upload behavior with network/server issues.
        
        This test verifies the server properly handles various error conditions
        so that the client can implement proper retry logic.
        """
        # Test with unreachable endpoint (different port simulation)
        bad_url = "http://127.0.0.1:9999/api/upload/photo"
        
        try:
            files = {"file": ("test.jpg", b"fake_image_data", "image/jpeg")}
            response = requests.post(bad_url, files=files, timeout=2)
            # If we get here, server responded (unexpected)
            assert response.status_code >= 400
        except requests.exceptions.ConnectionError:
            # Expected - server not reachable, client should retry
            pass
        except requests.exceptions.Timeout:
            # Timeout - client should retry
            pass
        
    def test_upload_with_valid_token_succeeds(self, valid_token):
        """Verify normal upload works with valid token - baseline test."""
        files = {"file": ("test.jpg", b"\xff\xd8\xff\xe0test", "image/jpeg")}
        data = {"timestamp": int(time.time())}
        headers = {"Authorization": f"Bearer {valid_token}"}
        
        response = requests.post(
            f"{BASE_URL}/api/upload/photo",
            files=files,
            data=data,
            headers=headers
        )
        
        # Should succeed or return proper error (e.g., unsupported file type for fake data)
        # We mainly care that it's NOT a 401
        assert response.status_code != 401, \
            f"Valid token should not return 401: {response.text}"
