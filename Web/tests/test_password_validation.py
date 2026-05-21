"""Tests for server-side password validation (W-1)."""
import pytest
import requests
import time
import os


BASE_URL = os.environ.get("LANSYNC_TEST_SERVER", "http://127.0.0.1:8765")
# Use alphanumeric username only (server validates this)
TEST_USER = f"testuser{int(time.time())}"
TEST_PASSWORD = "testpassword123"
MISMATCH_PASSWORD = "testpassword456"


class TestPasswordValidation:
    """Test that server enforces password confirmation validation."""

    def test_register_password_mismatch_rejected_by_server(self):
        """Verify server rejects registration when password and confirm_password don't match.
        
        This is a server-side validation test - the server should reject
        registration requests where password and confirm_password fields
        have different values, not just rely on client-side validation.
        """
        # Clean up any existing user first (ignore errors)
        try:
            requests.post(f"{BASE_URL}/api/login", json={
                "username": TEST_USER,
                "password": TEST_PASSWORD
            })
        except:
            pass
        
        # Attempt registration with mismatched passwords
        response = requests.post(f"{BASE_URL}/api/register", json={
            "username": TEST_USER,
            "password": TEST_PASSWORD,
            "confirm_password": MISMATCH_PASSWORD
        })
        
        # Server should reject the mismatched passwords
        assert response.status_code == 400, \
            f"Expected 400 for password mismatch, got {response.status_code}: {response.text}"
        
        data = response.json()
        assert "error" in data or "message" in data, \
            f"Expected error message, got: {data}"
        
        # Verify the user was NOT registered by trying to login with the original password
        login_resp = requests.post(f"{BASE_URL}/api/login", json={
            "username": TEST_USER,
            "password": TEST_PASSWORD
        })
        assert login_resp.status_code == 401, \
            f"User should not be registered with mismatched passwords, got status: {login_resp.status_code}"
