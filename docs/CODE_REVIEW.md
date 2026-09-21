# Code Review 紀錄

## 2026-09-21：v0.2.1

檢視範圍：BLE/GATT lifecycle、session 邊界與平均、SQLite migration／outbox、WorkManager retry、Supabase Edge Functions、權限與機密資料邊界。

### 已修正

| 嚴重度 | 發現 | 修正 |
|---|---|---|
| 中 | 已連線時 UI 仍可再次啟動掃描，可能產生多餘的掃描逾時事件。 | UI 與 `BleManager` 雙層阻止已連線時重複掃描。 |
| 中 | 直接連線偏好裝置沒有 timeout；裝置離線時可能長時間停在連線階段。 | 新增 20 秒 GATT 連線 timeout，關閉過期連線並交由既有指數退避重試。 |
| 中 | GATT callback 與主執行緒的 2.5 秒 timer 可能同時讀寫 session 樣本。 | 以專用 lock 原子複製及清空 session，避免漏算或並行修改。 |
| 低 | GATT operation queue 可能從 UI 與 callback 執行緒同時推進。 | 將 queue 的 enqueue、next 與 completion 序列化。 |
| 低 | 舊 GATT 在逾時後回傳 callback，可能干擾新連線。 | callback 先驗證是否仍是目前 GATT；過期 instance 只關閉、不改動目前狀態。 |

### 已確認

- DB version 1 升級至 version 2 只新增資料表及索引，不刪除既有量測。
- session 與 outbox 在同一 transaction 內建立，避免只有其中一邊成功。
- API 的 upload/read key 分離；資料庫僅保存 key hash。
- Edge Functions 使用 service role 存取啟用 RLS 且沒有 anon policy 的資料表。
- v0.2 payload 不含 GPS；ingestion 固定將 `metadata` 寫成空物件。
- API URL、anon key、upload key、read key、Supabase CLI 暫存與實機 feedback 均被排除於 Git。
- 30 天清理同時涵蓋 measurements、events、sessions 與 outbox。

### 保留風險

- Android BLE stack 仍可能因廠牌省電策略停止背景工作，需要更多機型實測。
- 未送達 outbox 最長也只保留 30 天，這是容量上限與離線耐受度之間的既定取捨。
- Debug APK 內的 upload key 可被裝置持有人取得；其權限僅限寫入、可獨立撤銷，不能用來讀取量測資料。
- 開機後自動啟動 Foreground Service 尚未實作，目前需由使用者在 App 內啟用。
