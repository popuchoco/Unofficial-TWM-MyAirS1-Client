import { adminClient, authorize, cors, json } from "../_shared/auth.ts";

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  if (!await authorize(request, "upload")) return json({ error: "unauthorized" }, 401);
  const body = await request.json().catch(() => null);
  if (!body || body.schema_version !== 1 || !body.event_id || !body.started_at || !body.ended_at ||
      !Number.isInteger(body.sample_count) || body.sample_count < 1 || !body.latest || !body.average) {
    return json({ error: "invalid_payload" }, 400);
  }
  const row = {
    event_id: body.event_id,
    device_id: String(body.device_id ?? "myair-s1").slice(0, 80),
    started_at: new Date(body.started_at).toISOString(),
    ended_at: new Date(body.ended_at).toISOString(),
    sample_count: body.sample_count,
    latest: body.latest,
    average: body.average,
    metadata: {},
  };
  const { error } = await adminClient().from("myair_measurement_sessions").upsert(row, { onConflict: "event_id" });
  return error ? json({ error: "storage_failed" }, 500) : json({ accepted: true, event_id: row.event_id }, 202);
});
