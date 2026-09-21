# 實機與 Supabase 驗證紀錄

## 範圍

本輪驗證涵蓋 Android Client、BLE session 彙整、transactional outbox、Supabase ingestion、受保護的最後量測 API，以及 Agent bridge。公開紀錄只保留驗證結論，不包含裝置廣播名稱、藍牙位址、真實量測值、實際量測時間、API 金鑰或其他可識別環境的資訊。

## 對應原始碼

| 驗證項目 | 實作位置 |
|---|---|
| BLE 掃描、連線、session 邊界、自動重連 | `app/src/main/java/com/kerberosclaw/myairs1/BleManager.kt` |
| session 平均、SQLite、outbox、30 天清理 | `app/src/main/java/com/kerberosclaw/myairs1/AppDatabase.kt` |
| HTTPS 上傳與 WorkManager retry | `app/src/main/java/com/kerberosclaw/myairs1/BackendUploader.kt` |
| Foreground Service | `app/src/main/java/com/kerberosclaw/myairs1/S1ForegroundService.kt` |
| 最新值、平均與量測區間畫面 | `app/src/main/java/com/kerberosclaw/myairs1/MainActivity.kt` |
| 資料表與 RLS | `supabase/migrations/202609210001_myair_measurements.sql` |
| 寫入／唯讀 API | `supabase/functions/ingest-measurement/index.ts`、`supabase/functions/last-measurement/index.ts` |
| Agent 唯讀 bridge | `bridge/myair_bridge.py` |
| session 計算測試 | `app/src/test/java/com/kerberosclaw/myairs1/SessionCalculatorTest.kt` |

## 實機結果

- 診斷格式為 schema version 2，可正常解析。
- 匯出資料包含六次可由 2.5 秒靜默間隔明確分離的量測 session。
- 最新 session 完整收到 28 筆樣本，開始與結束時間順序正確。
- App 顯示的樣本數、最後一筆數值、PM2.5／溫度／濕度平均值，均與離線逐筆重算結果一致。
- 手機本地時間與 Supabase UTC 時間相差預期時區偏移，代表時間轉換正確。
- 最新一輪未出現封包解析失敗、GATT 斷線或資料遺失跡象。

## 同步結果

1. App 完成 session 後，先在同一個 SQLite transaction 寫入 session 與 outbox。
2. WorkManager 使用 upload key 將 payload 送至 `ingest-measurement`。
3. Supabase 以 `event_id` 冪等保存資料。
4. Agent bridge 使用獨立 read key 呼叫 `last-measurement`。
5. 讀回的起訖時間、樣本數、最後值及三項平均值，與手機端逐欄一致。

另以人工建立的測試 session 驗證完整寫入／讀回流程；核對完成後已移除測試資料。正式資料庫只保留實際 App 同步的 session。

## 判定

本輪端到端流程通過。這項結果證明指定裝置與當時網路環境可正常運作，不等同所有 Android 裝置、韌體或網路條件均已覆蓋；後續版本仍需以去識別化診斷資料進行相容性驗證。

v0.2.1 修正完成後另執行乾淨建置、protocol／空氣品質級距／session calculator JVM tests 與 Debug APK 組裝，全部通過。session calculator 測試刻意以亂序樣本輸入，確認結果會按接收時間決定起訖與最後值，並使用區間內全部樣本計算平均。

v0.3.0 新增 Device Information parser 測試，使用 14-byte 範例核對 Protocol、Model、Software/Firmware 與 Hardware revision。完整 JVM suite 共 6 項測試，0 failure／0 error；configured Debug APK 組裝成功。圖表、CSV 文件選擇器、通知及實機版本欄位仍需以本版 APK 進行下一輪手機 UI 驗收。

## 本地排程與唯讀歷史同步（待實機驗收）

- JVM 測試已涵蓋歷史封包重組與每日固定時間的同日／跨日計算。
- `testDebugUnitTest` 與 `assembleDebug` 已通過。
- 程式碼確認未定義或送出裝置歷史清除命令。
- 歷史資料改用裝置不透明雜湊、序號、裝置時間、trigger 與內容 checksum 的事件指紋去重。
- 排程保存於 SQLite，Boot Receiver 只在仍有排程時恢復 Foreground Service。
- 尚待實機確認：歷史封包邊界、checksum status、裝置容量、重複匯入、手機重開機、10 分鐘斷線寬限與各廠牌省電策略。
- 本輪沒有實作或驗證 Supabase 遠端觸發。

## v0.3.3 歷史同步狀態修正

- 實機 diagnostic 顯示同步 ACK 宣告 22 個封包，22 個封包均已到達，但完成狀態沒有寫回 UI。
- 根因為 checksum 位置誤讀，加上 `Result.onSuccess` 內拋出的新例外不會交給後續 `onFailure`，導致 `historySyncing` 未重設。
- checksum status 已改讀最後一包的最後兩個 byte；新增 20 筆／22 封包邊界、非零 checksum 與超大宣告長度測試。
- 完成、失敗、逾時與斷線都會離開同步狀態；歷史資料仍不送清除命令。
- 歷史紀錄只寫入 observations，不建立 session／outbox，避免報表、CSV 與最後一次完整量測的語意混用。
- 總覽新增下拉重新整理 SQLite 最新完成 session。
