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
- [x] 可設定的一次性、週期性與每日定時量測
- [x] FIFO 任務派發、排程 SQLite 恢復、斷線寬限與未執行事件
- [x] 裝置歷史資料唯讀同步、封包重組與完整性檢查（待實機驗收）
- [x] 今日／近 30 日 CSV 匯出
- [x] 30 天資料保留政策
- [x] 非破壞性 SQLite migrations

## Phase 3：IoT／Smart Home 整合

- [x] Transactional outbox
- [x] Supabase ingestion／最後量測 read API 範本
- [x] upload token 與 Agent read token 分離
- [ ] Home Assistant sensor integration
- [ ] Node-RED flow example
- [ ] 裝置狀態與空氣品質 dashboard
- [ ] Supabase 遠端量測命令佇列（不屬於第一版本地排程）
- [x] 今日與近 30 日量測圖表
- [x] Material 3 五分頁及深淺色模式
- [x] BLE 斷線通知
- [x] 裝置版本資訊讀取
- [ ] 使用者主動設定站點的民間空氣地圖 adapter（不預設蒐集 GPS）
