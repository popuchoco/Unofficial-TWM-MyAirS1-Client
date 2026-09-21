"""Fetch the latest protected myAir S1 session and atomically cache it for an Agent."""
from __future__ import annotations
import json, os, pathlib, tempfile, urllib.request

url = os.environ["MYAIR_READ_API_URL"]
anon = os.environ["MYAIR_SUPABASE_ANON_KEY"]
read_key = os.environ["MYAIR_READ_KEY"]
output = pathlib.Path(os.environ.get("MYAIR_OUTPUT", "latest-measurement.json"))
request = urllib.request.Request(url, headers={"Authorization": f"Bearer {anon}", "apikey": anon, "X-MyAir-Key": read_key})
with urllib.request.urlopen(request, timeout=20) as response:
    payload = json.load(response)
output.parent.mkdir(parents=True, exist_ok=True)
with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=output.parent, delete=False) as tmp:
    json.dump(payload, tmp, ensure_ascii=False, indent=2)
    temporary = pathlib.Path(tmp.name)
temporary.replace(output)
print(output.resolve())
