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
    if (url.pathname === "/text" && req.method === "POST") {
      if (!env.APP_KEY || req.headers.get("x-app-key") !== env.APP_KEY) return json({ error: "forbidden" }, 403);
      let b; try { b = await req.json(); } catch { return json({ error: "bad_json" }, 400); }
      if (typeof b?.prompt !== "string" || b.prompt.length > 60000) return json({ error: "bad_prompt" }, 400);
      for (const m of [env.TEXT_MODEL || "gemini-flash-latest", "gemini-2.5-flash"]) {
        const r = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${m}:generateContent`, { method: "POST",
          headers: { "content-type": "application/json", "x-goog-api-key": env.GEMINI_API_KEY },
          body: JSON.stringify({ contents: [{ role: "user", parts: [{ text: b.prompt }] }], generationConfig: b.json ? { responseMimeType: "application/json", temperature: 0.2 } : {} }) });
        const d = await r.json().catch(() => ({}));
        const text = (d.candidates?.[0]?.content?.parts || []).map((p) => p.text || "").join("");
        if (r.ok && text) return json({ text });
      }
      return json({ error: "ai_failed" }, 502);
    }
    if (url.pathname === "/video" && req.method === "POST") {
      if (!env.APP_KEY || req.headers.get("x-app-key") !== env.APP_KEY) return json({ error: "forbidden" }, 403);
      // TODO(결제 연결 후): 구독 확인
      let b; try { b = await req.json(); } catch { return json({ error: "bad_json" }, 400); }
      if (typeof b?.image !== "string" || typeof b?.prompt !== "string") return json({ error: "bad_input" }, 400);
      const base = "https://generativelanguage.googleapis.com/v1beta";
      const H = { "content-type": "application/json", "x-goog-api-key": env.GEMINI_API_KEY };
      const find = (o, d = 0) => { if (!o || typeof o !== "object" || d > 12) return null; const mt = o.mime_type || o.mimeType || "";
        if ((o.type === "video" || mt.startsWith("video")) && o.data) return { data: o.data, mime: mt || "video/mp4" };
        for (const k in o) { const r = find(o[k], d + 1); if (r) return r; } return null; };
      const r = await fetch(`${base}/interactions`, { method: "POST", headers: H, body: JSON.stringify({ model: env.VIDEO_MODEL || "gemini-omni-1.1-flash",
        input: [{ type: "image", data: b.image, mime_type: "image/jpeg" }, { type: "text", text: b.prompt.slice(0, 4000) }],
        generation_config: { video_config: { task: "image_to_video" } },
        response_format: { type: "video", aspect_ratio: b.aspect_ratio === "16:9" ? "16:9" : "9:16", resolution: b.resolution === "1080p" ? "1080p" : "720p" } }) });
      let d = await r.json().catch(() => ({}));
      if (r.status === 429) return json({ error: "busy", message: d?.error?.message }, 429);
      if (!r.ok) return json({ error: "ai_failed", message: d?.error?.message || r.status }, 502);
      let v = find(d);
      for (let k = 0; !v && d?.id && k < 70; k++) {
        await new Promise((s) => setTimeout(s, 6000));
        d = await (await fetch(`${base}/interactions/${encodeURIComponent(d.id)}`, { headers: H })).json().catch(() => ({}));
        v = find(d);
      }
      return v ? json({ video: v.data, mime: v.mime }) : json({ error: "ai_failed", message: "no video" }, 502);
    }
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
