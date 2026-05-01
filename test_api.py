
import urllib.request
import json

# Login
data = json.dumps({"username": "photosync", "password": "sync123"}).encode()
req = urllib.request.Request("http://192.168.0.107:8765/api/login", data=data, headers={"Content-Type": "application/json"})
resp = urllib.request.urlopen(req, timeout=5)
body = json.loads(resp.read())
token = body["token"]
print("Token length:", len(token))
print("Token prefix:", token[:20])

# Test gallery
req2 = urllib.request.Request("http://192.168.0.107:8765/api/gallery/list", headers={"Authorization": f"Bearer {token}"})
resp2 = urllib.request.urlopen(req2, timeout=5)
body2 = json.loads(resp2.read())
print("Gallery response:", json.dumps(body2, indent=2)[:500])
