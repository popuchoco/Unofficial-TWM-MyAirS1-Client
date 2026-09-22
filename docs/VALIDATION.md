# 實機與 Supabase 驗證紀錄

## v0.3.6 更換裝置後手動量測（已通過）

`myair-s1-diagnostic_0.3.6.json` 已用 0.3.5 的失敗路徑完成實機複測：第一條 GATT 於服務探索後、notification 初始化尚未完成時進入「更換裝置」，新連線仍能建立自己的操作佇列並完成量測。

去識別化時間線：

1. 第一條 GATT 完成服務探索，隨即在初始化期間再次開始掃描。
2. 新裝置完成選擇、GATT 連線與量測服務探索。
3. Sensor Measurement 與 Control Point notification 均回報 `status=0`。
4. 裝置版本資訊讀取完成後，手動量測命令正常送出。
5. 約 35 秒後完成 28 筆量測串流；沒有量測逾時，也沒有舊 callback 推進或阻塞新連線佇列的跡象。

結論：0.3.5「更換裝置後手動命令未送出」問題已由 0.3.6-test 的 GATT 連線世代隔離修正，原始失敗情境已實機驗證通過。量測數值、裝置名稱、位址與實際封包未寫入公開文件。

## v0.3.5 更換裝置後手動量測未派發

`myair-s1-diagnostic_0.3.5_v1.json` 顯示：第一條 GATT 連線只完成 Sensor notification，使用者隨即進入「更換裝置」；第二條連線雖完成 service discovery，後續沒有 Control Point notification、版本讀取或「已送出手動量測命令」，最後僅由 60 秒量測計時器結束任務。`v2` 在 App 重啟後完成全部 GATT 初始化，手動命令與量測串流皆正常。

根因是更換裝置直接關閉舊 GATT 時，舊連線仍有一筆 descriptor write 等待 callback；共用的序列操作佇列因此維持 running，新連線的 notification、版本讀取及量測寫入全部排在其後而無法執行。v0.3.6-test 在連線世代切換時清空操作佇列，且所有 GATT callback 只允許作用於目前連線，避免舊 callback 干擾新佇列。

同輪修正亦包含：掃描達 15 秒但已有候選時直接結算候選、不在服務驗證成功前保存新偏好裝置，以及「忘記裝置」時停止 Foreground Service。上述更換裝置情境已由 0.3.6 實機複測通過。

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

## v0.3.4 歷史尾包遺失與重試

- 實機 diagnostic 顯示 ACK 宣告 25 個封包，實際只收到連續序號 `0x00`～`0x16`，共 23 包；缺少尾端 `0x17`、`0x18`，其後約 56 秒沒有新封包才觸發原本的 60 秒 Timeout。
- 同一份紀錄較早的同步為 22/22 完整，因此判定為 BLE burst 尾包偶發遺失，而非 App 提早逾時、長度誤判或 checksum 卡住。
- 改為連續 10 秒無進度後重新要求完整唯讀批次，最多三次；畫面顯示嘗試次數與已收／預期封包數。
- 新增 retry policy 測試，覆蓋 23/25、第一／第二次重試、第三次失敗與斷線不重試。
- 歷史 observation 改成單一 transaction 批次寫入並只清理一次；成功後不再無效喚醒 outbox worker。
- SQLite 寫入移出 GATT callback thread，time sync 也會佔用 busy 狀態，避免與量測 control-point 命令交錯。

## v0.3.5 多裝置選擇與歷史同步停案界線

- 0.3.4 實機 diagnostic 的三次嘗試分別收到 `23/27`、`24/27`、`24/27`；最後兩次均只到連續序號 `0x17`，缺少預期尾端 `0x18`～`0x1A`。
- 重試確實重新收到 ACK 並從序號 `0x00` 開始；三次失敗後同步狀態及按鈕正常釋放，隨後手動量測成功。這排除 UI busy 鎖未釋放是本次主因。
- 已觀察事實：裝置宣告的數量大於實收連續封包數，且差異集中於批次尾端。合理推論：裝置可能存在未辨識的分頁、續傳、舊紀錄邊界或特定 firmware 行為。尚未證實：實際續傳命令與宣告欄位的完整語意。
- 安全決策：歷史同步停留在唯讀實驗功能；不匯入不完整批次、不送清除命令，也不猜測未驗證的續傳指令。即時與排程量測可獨立使用。
- 納入延遲 ACK／前次封包交錯風險：重試間等待 1.5 秒 quiet window、每次只接受一個 ACK、封包序號必須由 0 連續遞增。因 wire protocol 沒有 attempt ID，文件不宣稱可完全辨識封包世代。
- 新增 discovery policy 與 history sequence JVM 測試，覆蓋零候選持續掃描、單候選自動連線、多候選要求選擇，以及歷史序號起點／連續性。
- 多裝置 UI 尚待實機驗收：單台自動連線、多台選擇、上次使用標記、更換裝置、忘記裝置與背景重連抑制。
