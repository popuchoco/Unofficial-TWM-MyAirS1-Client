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

儲存量測與事件。資料庫位於 Android App 私有空間，App 更新時保留；未來 schema 變更必須採非破壞性 migration。

### UI and diagnostics

Jetpack Compose UI 顯示連線狀態與最新量測。診斷 Console 與 JSON 匯出用來協助裝置相容性測試。

## 背景連線與資料同步

Foreground Service 維持 BLE 連線；斷線後以指數退避重連已保存的偏好裝置。一次 session 以最後一筆通知後 2.5 秒為界，全部樣本先在同一 SQLite transaction 寫入 session 與 outbox，再由 WorkManager 送往使用者指定的 HTTPS API。伺服器確認後才標記完成，網路錯誤不阻塞 BLE 量測。

上傳金鑰和唯讀金鑰分離。Agent 不直接控制 BLE，而是透過受保護的 `last-measurement` API 或本機 bridge 快取取得最後一次 session；回應同時包含最新值、平均值、起訖時間及樣本數。

## 定位與未來擴充

v0.2 不讀取、保存或上傳 GPS 座標。後端保留不透明的 `metadata` 擴充欄位，但 ingestion 目前固定寫入空物件。未來若整合民間空氣地圖，須另行設計「使用者主動設定站點」模式及清楚的同意流程，不能由本版自動推定位置。
