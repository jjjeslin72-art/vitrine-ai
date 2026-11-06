// Vitrine AI — AI 서버 (출시용, Cloudflare Workers)
// 비밀값(GEMINI_API_KEY, APP_KEY, PLAY_SA_JSON)은 Cloudflare에만 있고 앱 안에는 없음.
//
// 하는 일
//  /generate  사진 만들기 (1장 = 1)
//  /video     동영상 만들기 (1개 = 10)
//  /text      글 만들기·번역·품질 검사 (무료, 횟수 제한만)
//
// 사용량 지키기 (KV "USAGE"와 PLAY_SA_JSON이 있을 때만 켜짐 — 없으면 테스트처럼 모두 허용)
//  - 구독 안 한 기기: 처음 FREE_TRIAL장까지
//  - 구독자: Google Play에서 결제 토큰을 확인한 뒤 한 달 basic 100 / pro 300
const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers": "content-type, x-app-key, x-device, x-plan-token",
};
const json = (o, status = 200) =>
  new Response(JSON.stringify(o), { status, headers: { "content-type": "application/json", ...CORS } });
const RATIOS = ["3:4", "4:5", "9:16", "1:1"];
const PKG = "com.juya.vitrine";
const QUOTA = { basic: 100, pro: 300 };
const GEM = "https://generativelanguage.googleapis.com/v1beta";

export default {
  async fetch(req, env) {
    if (req.method === "OPTIONS") return new Response(null, { headers: CORS });
    const url = new URL(req.url);
    if (url.pathname === "/health") return json({ ok: true });
    if (req.method !== "POST") return json({ error: "not_found" }, 404);
    if (!env.APP_KEY || req.headers.get("x-app-key") !== env.APP_KEY) return json({ error: "forbidden" }, 403);

    const device = (req.headers.get("x-device") || "anon").slice(0, 64);
    if (env.LIMITER) {
      const { success } = await env.LIMITER.limit({ key: device });
      if (!success) return json({ error: "too_fast" }, 429);
    }
    let body;
    try { body = await req.json(); } catch { return json({ error: "bad_json" }, 400); }

    if (url.pathname === "/text") return text(env, body);
    if (url.pathname === "/generate") return withQuota(env, req, device, 1, () => image(env, body));
    if (url.pathname === "/video") return withQuota(env, req, device, 10, () => video(env, body));
    return json({ error: "not_found" }, 404);
  },
};

/* ---------------- 사용량 ---------------- */
async function withQuota(env, req, device, cost, run) {
  const gate = await reserve(env, req, device, cost);
  if (!gate.ok) return json({ error: gate.error, message: gate.message }, gate.status);
  const res = await run();
  if (res.status >= 400 && gate.undo) await gate.undo();   // 실패하면 사용량을 돌려줌
  return res;
}

async function reserve(env, req, device, cost) {
  const kv = env.USAGE;
  if (!kv) return { ok: true };                      // 테스트: 제한 없음
  const month = new Date().toISOString().slice(0, 7);
  const token = (req.headers.get("x-plan-token") || "").slice(0, 400);
  let plan = "none";
  if (token && env.PLAY_SA_JSON) plan = await planOf(env, token);
  const key = plan !== "none" ? `use:${await sha(token)}:${month}` : `free:${device}`;
  const limit = plan !== "none" ? QUOTA[plan] : Number(env.FREE_TRIAL || 5);
  const used = Number((await kv.get(key)) || 0);
  if (used + cost > limit) {
    return { ok: false, status: 402, error: plan !== "none" ? "month_over" : "trial_over", message: `${used}/${limit}` };
  }
  await kv.put(key, String(used + cost), { expirationTtl: plan !== "none" ? 60 * 60 * 24 * 40 : 60 * 60 * 24 * 365 });
  return { ok: true, undo: async () => { const u = Number((await kv.get(key)) || 0); await kv.put(key, String(Math.max(0, u - cost))); } };
}

// Google Play에 구독이 살아 있는지 물어봄 (6시간 동안 기억)
async function planOf(env, token) {
  const kv = env.USAGE;
  const ck = `ent:${await sha(token)}`;
  const cached = kv && (await kv.get(ck));
  if (cached) return cached;
  let plan = "none";
  try {
    const at = await googleToken(env);
    const r = await fetch(`https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${PKG}/purchases/subscriptionsv2/tokens/${encodeURIComponent(token)}`,
      { headers: { authorization: `Bearer ${at}` } });
    const d = await r.json().catch(() => ({}));
    const live = ["SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD"].includes(d.subscriptionState);
    const ids = (d.lineItems || []).map((x) => x.productId);
    if (live) plan = ids.includes("vitrine_pro") ? "pro" : ids.includes("vitrine_basic") ? "basic" : "none";
  } catch { plan = "none"; }
  if (kv) await kv.put(ck, plan, { expirationTtl: 60 * 60 * 6 });
  return plan;
}

let gTok = null, gExp = 0;
async function googleToken(env) {
  if (gTok && Date.now() < gExp) return gTok;
  const sa = JSON.parse(env.PLAY_SA_JSON);
  const now = Math.floor(Date.now() / 1000);
  const enc = (o) => b64url(new TextEncoder().encode(JSON.stringify(o)));
  const unsigned = `${enc({ alg: "RS256", typ: "JWT" })}.${enc({ iss: sa.client_email, scope: "https://www.googleapis.com/auth/androidpublisher", aud: "https://oauth2.googleapis.com/token", iat: now, exp: now + 3600 })}`;
  const der = Uint8Array.from(atob(sa.private_key.replace(/-----[^-]+-----|\s/g, "")), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey("pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const sig = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(unsigned)));
  const r = await fetch("https://oauth2.googleapis.com/token", { method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" },
    body: `grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=${unsigned}.${b64url(sig)}` });
  const d = await r.json();
  gTok = d.access_token; gExp = Date.now() + (d.expires_in - 120) * 1000;
  return gTok;
}
const b64url = (bytes) => btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
async function sha(s) {
  const h = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s)));
  return [...h].slice(0, 16).map((b) => b.toString(16).padStart(2, "0")).join("");
}

/* ---------------- AI ---------------- */
function imageParts(images, max) {
  const parts = [];
  if (!Array.isArray(images) || images.length > max) return null;
  for (const im of images) {
    if (!im || typeof im.data !== "string" || im.data.length < 100 || im.data.length > 7_000_000) return null;
    parts.push({ inline_data: { mime_type: im.mime || "image/jpeg", data: im.data } });
  }
  return parts;
}

async function text(env, b) {
  if (typeof b?.prompt !== "string" || b.prompt.length > 60000) return json({ error: "bad_prompt" }, 400);
  const imgs = imageParts(b.images || [], 4);
  if (!imgs) return json({ error: "bad_images" }, 400);
  for (const m of [env.TEXT_MODEL || "gemini-flash-latest", "gemini-2.5-flash"]) {
    const r = await fetch(`${GEM}/models/${m}:generateContent`, { method: "POST",
      headers: { "content-type": "application/json", "x-goog-api-key": env.GEMINI_API_KEY },
      body: JSON.stringify({ contents: [{ role: "user", parts: [{ text: b.prompt }, ...imgs] }], generationConfig: b.json ? { responseMimeType: "application/json", temperature: 0.2 } : {} }) });
    const d = await r.json().catch(() => ({}));
    const t = (d.candidates?.[0]?.content?.parts || []).map((p) => p.text || "").join("");
    if (r.ok && t) return json({ text: t });
  }
  return json({ error: "ai_failed" }, 502);
}

async function image(env, b) {
  const { prompt, images = [], ratio = "3:4" } = b || {};
  if (typeof prompt !== "string" || prompt.length < 10 || prompt.length > 4000) return json({ error: "bad_prompt" }, 400);
  const imgs = imageParts(images, 6);
  if (!imgs) return json({ error: "bad_images" }, 400);
  const parts = [{ text: prompt }, ...imgs];
  let lastErr = "no_model";
  for (const model of [env.MODEL, env.FALLBACK_MODEL].filter(Boolean)) {
    try {
      const r = await fetch(`${GEM}/models/${model}:generateContent`, {
        method: "POST",
        headers: { "content-type": "application/json", "x-goog-api-key": env.GEMINI_API_KEY },
        body: JSON.stringify({ contents: [{ role: "user", parts }],
          generationConfig: { responseModalities: ["IMAGE"], imageConfig: { aspectRatio: RATIOS.includes(ratio) ? ratio : "3:4", imageSize: "1K" } } }),
      });
      const data = await r.json().catch(() => ({}));
      if (r.status === 429) return json({ error: "busy", message: data?.error?.message }, 429);
      if (!r.ok) { lastErr = data?.error?.message || `http_${r.status}`; continue; }
      const out = (data.candidates?.[0]?.content?.parts || []).find((p) => p.inlineData || p.inline_data);
      const img = out?.inlineData || out?.inline_data;
      if (!img?.data) { lastErr = data.candidates?.[0]?.finishReason || data.promptFeedback?.blockReason || "no_image"; continue; }
      return json({ image: img.data, mime: img.mimeType || img.mime_type || "image/png", model });
    } catch (e) { lastErr = String(e?.message || e); }
  }
  return json({ error: "ai_failed", message: lastErr }, 502);
}

async function video(env, b) {
  if (typeof b?.image !== "string" || typeof b?.prompt !== "string") return json({ error: "bad_input" }, 400);
  const H = { "content-type": "application/json", "x-goog-api-key": env.GEMINI_API_KEY };
  const find = (o, d = 0) => {
    if (!o || typeof o !== "object" || d > 12) return null;
    const mt = o.mime_type || o.mimeType || "";
    if ((o.type === "video" || mt.startsWith("video")) && o.data) return { data: o.data, mime: mt || "video/mp4" };
    for (const k in o) { const r = find(o[k], d + 1); if (r) return r; }
    return null;
  };
  const r = await fetch(`${GEM}/interactions`, { method: "POST", headers: H, body: JSON.stringify({ model: env.VIDEO_MODEL || "gemini-omni-1.1-flash",
    input: [{ type: "image", data: b.image, mime_type: "image/jpeg" }, { type: "text", text: b.prompt.slice(0, 4000) }],
    generation_config: { video_config: { task: "image_to_video" } },
    response_format: { type: "video", aspect_ratio: b.aspect_ratio === "16:9" ? "16:9" : "9:16", resolution: b.resolution === "1080p" ? "1080p" : "720p" } }) });
  let d = await r.json().catch(() => ({}));
  if (r.status === 429) return json({ error: "busy", message: d?.error?.message }, 429);
  if (!r.ok) return json({ error: "ai_failed", message: d?.error?.message || r.status }, 502);
  let v = find(d);
  for (let k = 0; !v && d?.id && k < 70; k++) {
    await new Promise((s) => setTimeout(s, 6000));
    d = await (await fetch(`${GEM}/interactions/${encodeURIComponent(d.id)}`, { headers: H })).json().catch(() => ({}));
    if (d?.status === "failed") return json({ error: "ai_failed", message: d?.error?.message || "failed" }, 502);
    v = find(d);
  }
  return v ? json({ video: v.data, mime: v.mime }) : json({ error: "ai_failed", message: "no video" }, 502);
}
