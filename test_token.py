
import jwt
from datetime import datetime, timedelta, timezone

secret = "your-secret-key-change-in-production"
payload = {
    "username": "photosync",
    "exp": datetime.now(timezone.utc) + timedelta(hours=720),
    "iat": datetime.now(timezone.utc),
}
token = jwt.encode(payload, secret, algorithm="HS256")
print("Token:", token)

# Now verify it
try:
    decoded = jwt.decode(token, secret, algorithms=["HS256"])
    print("Decoded:", decoded)
except Exception as e:
    print("Error:", e)
