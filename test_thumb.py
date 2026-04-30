
import urllib.request
import json

# Login
data = json.dumps({"username": "photosync", "password": "sync123"}).encode()
req = urllib.request.Request("http://192.168.0.107:8765/api/login", data=data, headers={"Content-Type": "application/json"})
resp = urllib.request.urlopen(req, timeout=5)
token = json.loads(resp.read())["token"]

# Get gallery
req2 = urllib.request.Request("http://192.168.0.107:8765/api/gallery/list", headers={"Authorization": f"Bearer {token}"})
resp2 = urllib.request.urlopen(req2, timeout=5)
gallery = json.loads(resp2.read())

groups = gallery.get("groups", [])
if groups:
    photos = groups[0].get("photos", [])
    if photos:
        photo = photos[0]
        print(f"Testing photo: {photo['name']}")
        
        # Try to get thumbnail
        thumb_path = photo["path"]
        thumb_url = f"http://192.168.0.107:8765/api/gallery/thumb/{thumb_path.replace('/', '%2F')}"
        print(f"Thumb URL: {thumb_url}")
        
        req3 = urllib.request.Request(thumb_url, headers={"Authorization": f"Bearer {token}"})
        try:
            resp3 = urllib.request.urlopen(req3, timeout=10)
            data = resp3.read(100)
            print(f"Thumb data (first 100 bytes): {data}")
            print(f"Thumb content-type: {resp3.headers.get('Content-Type')}")
        except Exception as e:
            print(f"Thumb error: {e}")
