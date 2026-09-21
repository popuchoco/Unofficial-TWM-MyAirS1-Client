# Roadmap

## Phase 1：手機端資料識別（已完成）

- [x] BLE 掃描、連線與 service discovery
- [x] Sensor／Control Point notification
- [x] 單次量測與感測資料解析
- [x] SQLite 量測與事件紀錄
- [x] 可捲動診斷 Console 與 JSON 匯出
- [x] 裝置時間可信度判定
- [x] 手動 time-sync frame
- [x] 實機驗證基本 session 與連續量測串流

## Phase 2：可靠 datalogger

- [x] 保存偏好裝置並優先重連
- [x] Foreground Service 與斷線自動重連
- [ ] 可設定的定時量測
- [ ] 裝置歷史資料同步與完整性檢查
- [ ] CSV 匯出
- [x] 30 天資料保留政策
- [x] 非破壞性 SQLite migrations

## Phase 3：IoT／Smart Home 整合

- [x] Transactional outbox
- [x] Supabase ingestion／最後量測 read API 範本
- [x] upload token 與 Agent read token 分離
- [ ] Home Assistant sensor integration
- [ ] Node-RED flow example
- [ ] 裝置狀態與空氣品質 dashboard
- [ ] 使用者主動設定站點的民間空氣地圖 adapter（不預設蒐集 GPS）
