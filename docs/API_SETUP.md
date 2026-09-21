# Supabase 同步設定

## 部署

1. 以 Supabase CLI 套用 `supabase/migrations`。
2. 部署 `ingest-measurement` 與 `last-measurement` Edge Functions。
3. 為 upload 與 read 各產生一組至少 32 bytes 的隨機金鑰。
4. 只將 SHA-256 十六進位雜湊寫入 `myair_api_keys`；原始金鑰分別交給 App 與 bridge。

```sql
insert into public.myair_api_keys (key_hash, scope, label)
values
  (encode(digest('REPLACE_WITH_UPLOAD_KEY', 'sha256'), 'hex'), 'upload', 'phone'),
  (encode(digest('REPLACE_WITH_READ_KEY', 'sha256'), 'hex'), 'read', 'agent-bridge');
```

不要把範例中的原始金鑰提交至 Git。Edge Functions 需要平台提供的 `SUPABASE_URL` 與 `SUPABASE_SERVICE_ROLE_KEY`。

## Android 本機設定

在不納入版本控制的 `local.properties` 加入：

```properties
MYAIR_API_URL=https://PROJECT.supabase.co/functions/v1/ingest-measurement
MYAIR_SUPABASE_ANON_KEY=REPLACE_ME
MYAIR_UPLOAD_KEY=REPLACE_ME
```

三者任一留空時，App 繼續在本機保存 outbox，但不連線雲端。

## Agent bridge

依 `bridge/.env.example` 設定環境變數後執行 `python bridge/myair_bridge.py`。bridge 只呼叫唯讀端點，將回應原子寫入指定 JSON；回應包含最後一次量測 session 的：

- `started_at`、`ended_at`：量測時間區間。
- `sample_count`：區間中的樣本數。
- `latest`：區間最後一筆資料。
- `average`：該區間全部樣本的平均值。

v0.2 payload 沒有 GPS 欄位，伺服器亦不接受用戶端提供的位置 metadata。
