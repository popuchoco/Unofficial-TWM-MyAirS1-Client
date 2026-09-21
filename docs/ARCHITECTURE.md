# 系統架構

## 目標

在不依賴已停止維運之官方服務的前提下，讓既有 myAir S1 感測器成為可由使用者自行管理的 IoT 資料來源。

## 元件

```text
┌──────────────┐       BLE/GATT       ┌────────────────────────┐
│  myAir S1    │ ───────────────────▶ │ Android Client         │
│  Sensor      │ ◀─────────────────── │                        │
└──────────────┘                      │ - BLE controller       │
                                      │ - packet parser        │
                                      │ - SQLite repository    │
                                      │ - Compose UI           │
                                      │ - session aggregator   │
                                      │ - task coordinator     │
                                      │ - local scheduler      │
                                      │ - transactional outbox │
                                      └───────────┬────────────┘
                                                  │ authenticated HTTPS
                                                  ▼
                                      ┌────────────────────────┐
                                      │ Supabase Edge Function │
                                      │ read API / Agent bridge│
                                      └────────────────────────┘
```

### BLE controller

負責掃描、連線、服務探索、通知訂閱、GATT 操作序列化、量測命令與連線事件紀錄。

### Packet parser

將固定長度的感測資料轉成型別化 `Measurement`。原始十六進位內容仍會保留，以便跨韌體版本診斷。

### SQLite repository

儲存量測、session、事件、outbox 與目前排程。資料庫位於 Android App 私有空間，App 更新時保留；schema 變更採 migration。歷史量測以「不透明裝置代號＋序號＋裝置時間＋trigger＋內容 SHA-256」形成事件指紋，不再只以 `raw_hex` 判斷重複。

### UI and diagnostics

Jetpack Compose UI 顯示連線狀態與最新量測。診斷 Console 與 JSON 匯出用來協助裝置相容性測試。

## 背景連線與資料同步

Foreground Service 維持 BLE 連線；斷線後以指數退避重連已保存的偏好裝置。一次 session 以最後一筆通知後 2.5 秒為界，全部樣本先在同一 SQLite transaction 寫入 session 與 outbox，再由 WorkManager 送往使用者指定的 HTTPS API。伺服器確認後才標記完成，網路錯誤不阻塞 BLE 量測。

上傳金鑰和唯讀金鑰分離。Agent 不直接控制 BLE，而是透過受保護的 `last-measurement` API 或本機 bridge 快取取得最後一次 session；回應同時包含最新值、平均值、起訖時間及樣本數。

## 本地排程與任務派發

手動與本地 Timer 都建立 `MeasurementRequest`，由 `MeasurementCoordinator` 以 FIFO 依序派發；BLE 層同一時間只執行一筆量測。畫面顯示目前任務與等待數，佇列上限為 8。每筆量測 60 秒逾時後釋放下一筆，排程任務另有到期時間，過期只寫入未執行事件，不在恢復連線後大量補做。

排程保存在 SQLite，支援一次性、固定間隔與每日固定時間。Foreground Service 每 5 秒檢查到期項目；只有任務成功進入佇列後才推進下一次時間，所以忙碌狀態不會覆蓋或重設 Timer。斷線時沿用自動重連，預設寬限 10 分鐘；超時後記錄 `schedule_missed`。`BOOT_COMPLETED` 只在資料庫仍有排程時恢復服務。

## 裝置歷史同步安全界線

歷史同步會訂閱獨立 characteristic、取得封包數、重組資料並檢查裝置回報的 checksum status，再以既有 18-byte parser 匯入。第一版固定為唯讀：程式沒有定義或送出清除歷史資料命令。封包解析、checksum 或資料庫寫入失敗時保留裝置端資料，等待下一輪實機驗證。

## 遠端觸發擴充點（未實作）

資料模型預留 `remote_command` 來源，未來可讓受保護的 Supabase command queue 與本地排程共用 `MeasurementCoordinator`。目前沒有命令資料表、Edge Function、Realtime／FCM 訂閱或遠端控制憑證；現有 Supabase 功能仍只有量測上傳與最後結果唯讀 API。

## 定位與未來擴充

v0.2 不讀取、保存或上傳 GPS 座標。後端保留不透明的 `metadata` 擴充欄位，但 ingestion 目前固定寫入空物件。未來若整合民間空氣地圖，須另行設計「使用者主動設定站點」模式及清楚的同意流程，不能由本版自動推定位置。

## 韌體邊界

台灣大哥大未公開提供 myAir S1 的獨立韌體下載檔或手動更新工具，原智慧家庭系統亦已不再維護本裝置。本專案只處理感測資料與連線，不設計韌體下載、版本檢查、更新、降版或刷寫流程，也不把不明來源的映像檔納入工具鏈。
