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
                                      │ - diagnostic export    │
                                      └───────────┬────────────┘
                                                  │ planned HTTPS
                                                  ▼
                                      ┌────────────────────────┐
                                      │ Self-hosted API        │
                                      │ Smart Home / Agent     │
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

## 未來資料同步

規劃使用 transactional outbox：量測先寫入 SQLite，再由背景工作送往使用者指定的 HTTPS API；伺服器確認後才標記完成。斷線、手機休眠或伺服器暫時不可用時，不應阻塞 BLE 量測。

Agent 不直接控制 BLE，而是透過自架 API 的唯讀端點取得最新或歷史資料，以縮小權限與網路暴露面。
