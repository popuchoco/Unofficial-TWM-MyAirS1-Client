create extension if not exists pgcrypto;

create table if not exists public.myair_api_keys (
  id uuid primary key default gen_random_uuid(),
  key_hash text unique not null,
  scope text not null check (scope in ('upload', 'read')),
  label text not null default '',
  created_at timestamptz not null default now(),
  revoked_at timestamptz
);

create table if not exists public.myair_measurement_sessions (
  event_id uuid primary key,
  device_id text not null,
  started_at timestamptz not null,
  ended_at timestamptz not null,
  sample_count integer not null check (sample_count > 0),
  latest jsonb not null,
  average jsonb not null,
  metadata jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  check (ended_at >= started_at)
);

create index if not exists myair_sessions_latest on public.myair_measurement_sessions (ended_at desc);
alter table public.myair_api_keys enable row level security;
alter table public.myair_measurement_sessions enable row level security;

-- No anon/authenticated policies are created. Edge Functions use service_role.
-- Reserved metadata is intentionally empty in v0.2; GPS is neither collected nor uploaded.
