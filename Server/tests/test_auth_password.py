"""Test password hashing with argon2 upgrade."""
import pytest
import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from auth import _hash_password, verify_user_v2, register_user


class TestArgon2Hashing:
    """Test argon2 password hashing."""

    def test_hash_password_returns_argon2_format(self):
        """Verify _hash_password() returns hash starting with $argon2."""
        salt = "test_salt"
        hash_result = _hash_password("password", salt)
        assert hash_result.startswith("$argon2"), \
            f"Expected hash to start with $argon2, got: {hash_result}"

    def test_verify_password_correct(self):
        """Verify correct password passes verification."""
        username = "testusercorrect"
        # Clean up if exists
        users_file = os.path.join(os.path.dirname(os.path.dirname(__file__)), "users.json")
        import json
        if os.path.exists(users_file):
            with open(users_file, "r") as f:
                users = json.load(f)
            if username in users:
                del users[username]
                with open(users_file, "w") as f:
                    json.dump(users, f, indent=2)
        
        register_user(username, "correctpassword")
        assert verify_user_v2(username, "correctpassword") is True
        
        # Cleanup
        with open(users_file, "r") as f:
            users = json.load(f)
        if username in users:
            del users[username]
            with open(users_file, "w") as f:
                json.dump(users, f, indent=2)

    def test_verify_password_wrong(self):
        """Verify wrong password fails verification."""
        username = "testuserwrong"
        users_file = os.path.join(os.path.dirname(os.path.dirname(__file__)), "users.json")
        import json
        if os.path.exists(users_file):
            with open(users_file, "r") as f:
                users = json.load(f)
            if username in users:
                del users[username]
                with open(users_file, "w") as f:
                    json.dump(users, f, indent=2)
        
        register_user(username, "correctpassword")
        assert verify_user_v2(username, "wrongpassword") is False
        
        # Cleanup
        with open(users_file, "r") as f:
            users = json.load(f)
        if username in users:
            del users[username]
            with open(users_file, "w") as f:
                json.dump(users, f, indent=2)

    def test_different_passwords_different_hashes(self):
        """Verify same password produces different hashes each time (random salt)."""
        password = "same_password"
        salt1 = os.urandom(16).hex()
        salt2 = os.urandom(16).hex()
        hash1 = _hash_password(password, salt1)
        hash2 = _hash_password(password, salt2)
        assert hash1 != hash2, \
            f"Expected different hashes for same password, got both: {hash1}"

    def test_legacy_salt_hash_format_still_works(self):
        """Verify backward compatibility with old salt:hash format."""
        # Old format stored as "salt:hash" in the password_hash field
        # The verify_user_v2 should still work with existing users
        username = "testuser_legacy"
        users_file = os.path.join(os.path.dirname(os.path.dirname(__file__)), "users.json")
        import json
        
        # Create a legacy format user manually
        if os.path.exists(users_file):
            with open(users_file, "r") as f:
                users = json.load(f)
        else:
            users = {}
        
        # Create a user with old SHA256 format for backward compat test
        # Note: After upgrade, verify_user_v2 needs to handle both old and new formats
        if username in users:
            del users[username]
        
        # Old SHA256 format for backward compat
        import hashlib
        salt = "legacy_salt_12345"
        legacy_hash = hashlib.sha256(("test_password" + salt).encode()).hexdigest()
        
        users[username] = {
            "salt": salt,
            "password_hash": legacy_hash
        }
        with open(users_file, "w") as f:
            json.dump(users, f, indent=2)
        
        # Should still work with old format
        assert verify_user_v2(username, "test_password") is True
        
        # Cleanup
        with open(users_file, "r") as f:
            users = json.load(f)
        if username in users:
            del users[username]
            with open(users_file, "w") as f:
                json.dump(users, f, indent=2)
