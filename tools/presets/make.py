"""기본 모델 6명을 Gemini로 만들어 app/src/main/assets/presets.js 로 저장 (GitHub Actions에서 실행)."""
import base64, io, json, os, sys, time, urllib.request
from PIL import Image

KEY = os.environ["GEMINI_API_KEY"]
CFG = json.load(open("tools/presets/models.json", encoding="utf-8"))
ONLY = [x for x in os.environ.get("ONLY", "").split(",") if x]
OUT = "app/src/main/assets/presets.js"
MODELS = ["gemini-3.1-flash-image", "gemini-3-pro-image"]

def gen(prompt):
    body = json.dumps({"contents": [{"role": "user", "parts": [{"text": prompt}]}],
                       "generationConfig": {"responseModalities": ["IMAGE"], "imageConfig": {"aspectRatio": "3:4", "imageSize": "1K"}}}).encode()
    last = ""
    for m in MODELS:
        for attempt in range(3):
            req = urllib.request.Request(f"https://generativelanguage.googleapis.com/v1beta/models/{m}:generateContent", data=body,
                                         headers={"content-type": "application/json", "x-goog-api-key": KEY})
            try:
                d = json.load(urllib.request.urlopen(req, timeout=180))
            except urllib.error.HTTPError as e:
                last = e.read().decode()[:300]; print("  http", e.code, last); time.sleep(10); continue
            except Exception as e:
                last = str(e); print("  err", last); time.sleep(5); continue
            parts = ((d.get("candidates") or [{}])[0].get("content") or {}).get("parts") or []
            img = next((p.get("inlineData") or p.get("inline_data") for p in parts if p.get("inlineData") or p.get("inline_data")), None)
            if img:
                return base64.b64decode(img["data"])
            last = json.dumps(d)[:300]; print("  no image", last)
    raise SystemExit("failed: " + last)

old = {}
if os.path.exists(OUT):
    txt = open(OUT, encoding="utf-8").read()
    old = {m["id"]: m for m in json.loads(txt[txt.index("["):txt.rindex("]") + 1])}

out = []
for m in CFG["models"]:
    if ONLY and m["id"] not in ONLY and m["id"] in old:
        out.append(old[m["id"]]); continue
    print("making", m["name"])
    raw = gen(m["desc"] + " " + CFG["style"])
    im = Image.open(io.BytesIO(raw)).convert("RGB")
    im.thumbnail((900, 1200))
    buf = io.BytesIO(); im.save(buf, "JPEG", quality=86, optimize=True)
    out.append({"id": m["id"], "name": m["name"], "img": "data:image/jpeg;base64," + base64.b64encode(buf.getvalue()).decode()})
    print("  ok", len(buf.getvalue()) // 1024, "KB")

with open(OUT, "w", encoding="utf-8") as f:
    f.write(f"/* 기본 모델 (tools/presets/make.py 가 만듦 — 손으로 고치지 마세요) */\nwindow.PRESET_MODELS_V={CFG['version']};\nwindow.PRESET_MODELS=" + json.dumps(out, ensure_ascii=False) + ";\n")
print("saved", OUT)
