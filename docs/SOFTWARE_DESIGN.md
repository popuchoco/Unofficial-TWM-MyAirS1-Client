# Software Design（SD）

## 1. 範圍

Android Client 負責 myAir S1 的 BLE 連線、感測資料識別、本機保存與診斷。伺服器同步屬下一階段，介面預留但尚未啟用。

## 2. 模組

| 模組 | 檔案 | 責任 |
|---|---|---|
| Protocol | `Protocol.kt` | UUID、frame builder、measurement parser、資料模型 |
| BLE session | `BleManager.kt` | 掃描、連線、GATT queue、通知、量測狀態 |
| Persistence | `AppDatabase.kt` | SQLite schema、去重、event log、JSON export |
| UI | `MainActivity.kt` | 權限、操作入口、最新量測、診斷 Console |
| Future transport | `BackendUploader.kt` | 尚未接入 UI 的 HTTP transport prototype |

## 3. 狀態模型

`UiState` 是不可變快照，由 `StateFlow` 發送：

- `phase`：人類可讀的 session 階段。
- `scanning`／`connected`／`busy`：控制 UI 操作可用性。
- `latest`：最新成功解析的量測。
- `logs`：最多 80 筆、只供當次畫面呈現的診斷訊息。

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
- `INTERNET` 為未來自架同步預留；目前沒有預設遠端端點。

## 7. 預定 API contract

手機將主動上傳，避免在 Android 上長期暴露 HTTP server：

| Method | Path | 用途 |
|---|---|---|
| POST | `/api/v1/measurements` | 上傳量測，需 idempotency key |
| POST | `/api/v1/events` | 上傳連線與同步事件 |
| GET | `/api/v1/latest` | Agent 唯讀取得最新量測 |
| GET | `/api/v1/history` | Agent 唯讀查詢歷史 |
| GET | `/api/v1/status` | 最後上線、同步積壓與裝置狀態 |

傳輸層須使用 HTTPS 或可信任的私人網路，讀寫 token 分離，伺服器不得把 token 寫入 log。

## 8. 測試策略

- JVM unit tests：frame parser 與 time-sync builder。
- Android 實機：掃描、GATT status、CCCD、ACK、串流筆數與斷線重連。
- 後續 contract tests：outbox retry、重複上傳、伺服器錯誤與離線恢復。
