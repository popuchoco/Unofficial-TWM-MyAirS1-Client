# Code Review 紀錄

## v0.3.6-test：GATT 連線世代隔離

- 0.3.5 失敗紀錄證實，更換裝置時舊 descriptor write 未回呼會讓共用操作佇列永久維持執行中；新連線的量測命令因此從未送至裝置。
- 連線更換、忘記裝置、連線逾時與主動中斷現在都會重設 GATT 操作佇列。
- descriptor／characteristic read-write／notification callback 會先核對 callback 所屬 GATT 是否仍為目前連線，過期 callback 不得完成或推進新連線佇列。
- 新掃描候選只有在 service discovery 確認 myAir S1 量測服務後才保存為偏好裝置。
- 15 秒 scan timeout 若已找到候選，改為立即完成候選判定，不再丟棄 discovery window 內的結果。
- `GattOperationQueueTest` 覆蓋舊連線待辦被 reset 捨棄後，新連線可立即啟動操作的回歸情境。

## 2026-09-22：v0.3.5 多裝置選擇與歷史重試隔離

- 掃描不再命中第一台即連線；從第一個候選起固定收集 3 秒並依位址去重，單台自動連線，多台交由使用者選擇。
- 選擇清單只顯示名稱、RSSI 與上次使用標記；提供更換與二次確認的忘記裝置流程。切換期間抑制背景重連舊裝置。
- 歷史重試加入 quiet window、單 ACK gate 與連續 sequence gate，降低延遲 ACK／前次通知混入風險。
- 協定未提供 attempt ID；極晚抵達且恰好符合新嘗試形狀的 ACK 仍無法絕對歸屬，因此保留最多三次、完整批次才匯入與永不清除裝置資料的安全界線。
- 0.3.4 三輪實測均缺批次尾端，現階段不推測續傳命令，歷史同步標記為相容性受限的唯讀實驗功能。

## 2026-09-22：v0.3.4 歷史同步尾包重試

- 由實機時間線確認 Timeout 前只收到 23/25 包，缺少連續尾包，不是 UI 計時器提早觸發。
- 歷史同步改為 10 秒無進度、最多三次完整批次重試；每次丟棄前一輪不完整資料，避免跨批次拼接。
- 歷史 observations 採單一 transaction 批次匯入，30 天 prune 只執行一次，並移除沒有 outbox 資料時的 Worker 喚醒。
- BLE callback 的 SQLite 寫入移到單一背景 executor；session 保存完成後才釋放下一個量測任務。
- time sync 會暫時設定 `busy`，完成 characteristic write 後才恢復並續派等待中的量測。

## 2026-09-22：v0.3.3 實機 feedback 修正

- 修正歷史封包全部抵達後仍卡在同步中的狀態機錯誤；checksum 依相容性來源及實機尾包改讀最後兩個 byte。
- 將解析、checksum、匯入包在同一個 `runCatching`，確保任何階段失敗都會更新 UI 並清理同步狀態。
- 歷史資料改為 observation-only，不再為每筆 18-byte record 建立假 session 或送入 outbox。
- 歷史宣告長度上限為 1,000 筆，避免異常封包造成不合理配置。
- 即時 outbox work 改用 `APPEND_OR_REPLACE`，避免 `KEEP` 吃掉剛完成的新 session。
- BLE 掃描不再接受泛用 `S1` 名稱；time sync 在量測或歷史同步期間由程式層拒絕。
- 總覽加入下拉重新整理；電量以最近完成 session 為優先，不顯示未完成串流的殘留值。

## 2026-09-21：本地排程與唯讀歷史同步

- 手動與 Timer 統一由 `MeasurementCoordinator` FIFO 派發，等待上限 8 筆；量測完成、失敗或 60 秒逾時才釋放下一筆。
- Timer 成功進入佇列後才更新 SQLite 的下一次時間，避免 busy 時被覆蓋；每筆 Timer 帶 expiry，超過 10 分鐘寬限不補做。
- 一次性、週期性與每日排程都保存於 SQLite；手機開機後只有仍存在排程時才啟動服務。
- 歷史同步只送出讀取起始命令，未加入清除命令；checksum 或解析失敗保留裝置端資料。
- 移除 `raw_hex UNIQUE` 單一去重，改由不透明裝置 ID 與事件欄位建立 SHA-256 fingerprint。
- `remote_command` 只作型別擴充點；沒有遠端命令 API、Realtime、FCM 或控制憑證。

### 保留風險

- 歷史封包格式來自既有相容性資料，仍須以目前裝置韌體實測確認。
- Android／廠牌省電政策可能限制開機啟動或長時間 Foreground Service，不能視為硬即時排程。
- 目前只有一個有效本地排程 slot；建立新排程會明確取代舊排程，多排程功能留待後續版本。

## 2026-09-21：v0.3.0 UI／報告

- 報告只查詢已完成的 `measurement_sessions`，今日圖使用 session 平均、30 日圖使用每日平均。
- CSV 由 Android 文件選擇器寫入，不要求廣泛儲存權限。
- 斷線通知只在 Foreground Service 曾經連線後意外中斷時發送；使用者主動停止時抑制提醒。
- 通知權限與 BLE 權限判定分離，拒絕通知不會阻止前景 BLE 量測。
- 裝置版本優先讀取已知相容的自訂 Device Information characteristic，標準 Firmware Revision 僅作 fallback。
- Theme、斷線提醒偏好只保存在 App 私有 SharedPreferences。

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
- （v0.2.1 當時狀態）開機後自動啟動 Foreground Service 尚未實作；此項已於 v0.3.2 由 `BootReceiver` 完成。
