"""Tests for JWT Secret environment variable support (S-2)"""
import os
import pytest
import sys
from pathlib import Path

# Add parent directory to path for imports
sys.path.insert(0, str(Path(__file__).parent.parent))

from auth import _get_jwt_secret


class TestJwtSecretEnvVar:
    """Test JWT Secret environment variable configuration"""

    def test_jwt_secret_from_env_var(self, monkeypatch):
        """Verify LANSYNC_JWT_SECRET environment variable takes precedence over config"""
        # Set environment variable
        monkeypatch.setenv("LANSYNC_JWT_SECRET", "env_secret_value")
        
        # Config with a different secret
        config = {"auth": {"jwt_secret": "config_secret_value"}}
        
        # Should use env var, not config
        secret = _get_jwt_secret(config)
        assert secret == "env_secret_value", "LANSYNC_JWT_SECRET env var should take precedence over config"

    def test_jwt_secret_missing_raises_error(self, monkeypatch):
        """Verify missing JWT secret raises a clear ValueError"""
        # Ensure env var is not set
        monkeypatch.delenv("LANSYNC_JWT_SECRET", raising=False)
        
        # Config without jwt_secret
        config = {"auth": {}}
        
        with pytest.raises(ValueError) as excinfo:
            _get_jwt_secret(config)
        
        assert "JWT secret not configured" in str(excinfo.value)
        assert "LANSYNC_JWT_SECRET" in str(excinfo.value)

    def test_jwt_secret_fallback_to_config(self, monkeypatch):
        """Verify fallback to config when env var is not set"""
        # Ensure env var is not set
        monkeypatch.delenv("LANSYNC_JWT_SECRET", raising=False)
        
        # Config with jwt_secret
        config = {"auth": {"jwt_secret": "config_secret_value"}}
        
        secret = _get_jwt_secret(config)
        assert secret == "config_secret_value"

    def test_jwt_secret_empty_env_var_uses_config(self, monkeypatch):
        """Verify empty env var falls back to config"""
        # Set empty environment variable
        monkeypatch.setenv("LANSYNC_JWT_SECRET", "")
        
        config = {"auth": {"jwt_secret": "config_secret_value"}}
        
        secret = _get_jwt_secret(config)
        assert secret == "config_secret_value"
