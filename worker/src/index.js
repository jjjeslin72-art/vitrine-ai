// Vitrine AI — 옷 사진을 모델 착용샷으로 만들어 주는 서버 (출시용)
// 비밀값(GEMINI_API_KEY, APP_KEY)은 Cloudflare에만 있고 앱 안에는 없음
const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers": "content-type, x-app-key, x-device",
};
const json = (o, status = 200) =>
  new Response(JSON.stringify(o), { status, headers: { "content-type": "application/json", ...CORS } });
const RATIOS = ["3:4", "4:5", "9:16", "1:1"];

export default {
  async fetch(req, env) {
    if (req.method === "OPTIONS") return new Response(null, { headers: CORS });
    const url = new URL(req.url);
    if (url.pathname === "/health") return json({ ok: true });
    if (url.pathname !== "/generate" || req.method !== "POST") return json({ error: "not_found" }, 404);
    if (!env.APP_KEY || req.headers.get("x-app-key") !== env.APP_KEY) return json({ error: "forbidden" }, 403);

    const device = (req.headers.get("x-device") || "anon").slice(0, 64);
    if (env.LIMITER) {
      const { success } = await env.LIMITER.limit({ key: device });
      if (!success) return json({ error: "too_fast" }, 429);
    }
    // TODO(결제 연결 후): 여기서 구독·남은 장 수를 확인하고, 미결제 기기는 막기

    let body;
    try { body = await req.json(); } catch { return json({ error: "bad_json" }, 400); }
    const { prompt, images = [], ratio = "3:4" } = body || {};
    if (typeof prompt !== "string" || prompt.length < 10 || prompt.length > 4000) return json({ error: "bad_prompt" }, 400);
    if (!Array.isArray(images) || images.length > 4) return json({ error: "bad_images" }, 400);
    const parts = [{ text: prompt }];
    for (const im of images) {
      if (!im || typeof im.data !== "string" || im.data.length < 100 || im.data.length > 7_000_000) return json({ error: "bad_image" }, 400);
      parts.push({ inline_data: { mime_type: im.mime || "image/jpeg", data: im.data } });
    }

    const models = [env.MODEL, env.FALLBACK_MODEL].filter(Boolean);
    let lastErr = "no_model";
    for (const model of models) {
      try {
        const r = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`, {
          method: "POST",
          headers: { "content-type": "application/json", "x-goog-api-key": env.GEMINI_API_KEY },
          body: JSON.stringify({
            contents: [{ role: "user", parts }],
            generationConfig: { responseModalities: ["IMAGE"], imageConfig: { aspectRatio: RATIOS.includes(ratio) ? ratio : "3:4", imageSize: "1K" } },
          }),
        });
        const data = await r.json().catch(() => ({}));
        if (r.status === 429) return json({ error: "busy" }, 429);
        if (!r.ok) { lastErr = data?.error?.message || `http_${r.status}`; continue; }
        const out = (data.candidates?.[0]?.content?.parts || []).find((p) => p.inlineData || p.inline_data);
        const img = out?.inlineData || out?.inline_data;
        if (!img?.data) { lastErr = data.candidates?.[0]?.finishReason || data.promptFeedback?.blockReason || "no_image"; continue; }
        return json({ image: img.data, mime: img.mimeType || img.mime_type || "image/png", model });
      } catch (e) {
        lastErr = String(e?.message || e);
      }
    }
    return json({ error: "ai_failed", message: lastErr }, 502);
  },
};
