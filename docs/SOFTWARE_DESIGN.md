# Software Design（SD）

## 1. 範圍

Android Client 負責 myAir S1 的 BLE 連線、session 彙整、本機保存、診斷與可選同步。

## 2. 模組

| 模組 | 檔案 | 責任 |
|---|---|---|
| Protocol | `Protocol.kt` | UUID、frame builder、measurement parser、資料模型 |
| BLE session | `BleManager.kt` | 掃描、連線、GATT queue、通知、量測狀態 |
| Persistence | `AppDatabase.kt` | SQLite schema、session 平均、outbox、30 天清理 |
| UI | `MainActivity.kt` | 操作入口、最新值、平均值、時間區間、診斷 Console |
| Background | `S1ForegroundService.kt` | 常駐通知、BLE 自動重連 |
| Transport | `BackendUploader.kt` | HTTPS 上傳、WorkManager retry |

## 3. 狀態模型

`UiState` 是不可變快照，由 `StateFlow` 發送：

- `phase`：人類可讀的 session 階段。
- `scanning`／`connected`／`busy`：控制 UI 操作可用性。
- `latest`：正在量測時的最新樣本。
- `latestSession`：完成 session 的最新值、平均、起訖時間和樣本數。
- `logs`：最多 100 筆、只供當次畫面呈現的診斷訊息。

完整歷史由 SQLite 保存，不以 UI state 作為資料來源。

## 4. GATT concurrency

Android GATT API 每次只允許一個可靠的非同步操作。所有 descriptor write 與 characteristic write 進入 FIFO queue；callback 呼叫 `operationDone()` 後才執行下一項。

## 5. 資料一致性

- `raw_hex` 設為 unique，避免同一 notification 重複寫入。
- `received_at` 使用手機 wall clock；`device_epoch` 保留裝置原值。
- 裝置時間可信度由與接收時間的差值判定。
- 匯出包含 `schema_version`，後續變更可由 consumer 分支處理。

## 6. 權限

- Android 12+：`BLUETOOTH_SCAN`、`BLUETOOTH_CONNECT`。
- Android 11 以下：因平台 BLE 掃描限制使用 `ACCESS_FINE_LOCATION`，但程式不呼叫定位 API。
- `POST_NOTIFICATIONS`：Android 13+ 顯示 Foreground Service 狀態。
- `INTERNET`：只有填入自架端點與認證後才啟用同步；公開 build 沒有預設端點。
- 程式不使用 Android 定位 API，也不保存或上傳 GPS。

## 7. API contract

手機將主動上傳，避免在 Android 上長期暴露 HTTP server：

| Method | Path | 用途 |
|---|---|---|
| POST | `/functions/v1/ingest-measurement` | 以 `event_id` 冪等上傳完整 session |
| GET | `/functions/v1/last-measurement` | Agent 唯讀取得最後 session 的最新值與平均值 |

傳輸層使用 HTTPS，`X-MyAir-Key` 的 upload/read token 分離；資料庫只保存 SHA-256 雜湊，函式不得把 token 寫入 log。

## 8. 測試策略

- JVM unit tests：frame parser 與 time-sync builder。
- Android 實機：掃描、GATT status、CCCD、ACK、串流筆數與斷線重連。
- 後續 contract tests：outbox retry、重複上傳、伺服器錯誤與離線恢復。
