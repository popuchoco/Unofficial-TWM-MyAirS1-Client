import { adminClient, authorize, cors, json } from "../_shared/auth.ts";

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (request.method !== "GET") return json({ error: "method_not_allowed" }, 405);
  if (!await authorize(request, "read")) return json({ error: "unauthorized" }, 401);
  const { data, error } = await adminClient().from("myair_measurement_sessions")
    .select("event_id,device_id,started_at,ended_at,sample_count,latest,average,created_at")
    .order("ended_at", { ascending: false }).limit(1).maybeSingle();
  if (error) return json({ error: "storage_failed" }, 500);
  return data ? json({ measurement: data }) : json({ measurement: null });
});
