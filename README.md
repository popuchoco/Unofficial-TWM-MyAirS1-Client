# Unofficial TWM myAir S1 Client

一套為台灣大哥大 myAir S1 空氣品質感測器設計的非官方 Android 客戶端。

原廠 App 已停止維運，但既有感測器仍可繼續服務。這個專案以 IoT、Smart Home 與個人資料自主為出發點，建立全新的 BLE 資料識別、保存與同步流程，讓使用者能在手機上讀取感測資料，並逐步與 Home Assistant、Node-RED、自架 API 或其他自動化 Agent 整合。

> 本專案與台灣大哥大及原廠 App 開發者無關；產品名稱與商標分屬其權利人所有。

## 裝置規格摘要

- PM2.5 量測範圍：`0–500 µg/m³`。
- 無線連線：Bluetooth Low Energy 4.2。
- BLE 工作頻率：`2402–2480 MHz`。

## 專案狀態

目前是可安裝與實機測量的早期測試版本。基礎 BLE 連線、服務探索、通知訂閱、量測命令與資料解析已通過實機驗證。

目前驗證重點：

- 依實機 feedback 驗證歷史同步的封包邊界、重複資料與裝置容量；確認前維持唯讀。

已完成的開發里程碑見 [Roadmap](docs/ROADMAP.md)。

卡片級距參考[環境部空氣品質指標說明](https://airtw.moenv.gov.tw/CHT/Information/Standard/AirQualityIndicator.aspx)。官方 AQI 的即時 PM2.5 指標含移動平均公式，因此 App 不把單次感測值標示成 AQI。

## 架構

```text
myAir S1
    │ Bluetooth Low Energy
    ▼
Android Client
    ├── BLE session controller
    ├── measurement parser
    ├── private SQLite storage
    ├── diagnostic console / JSON export
    └── secure outbox
             │ HTTPS / private network
             ▼
       Supabase / bridge / Agent
```

- [架構文件](docs/ARCHITECTURE.md)
- [軟體設計文件](docs/SOFTWARE_DESIGN.md)
- [資料格式](docs/DATA_FORMAT.md)
- [安全與隱私](docs/SECURITY.md)
- [Supabase 與 Agent bridge 設定](docs/API_SETUP.md)
- [實機與 Supabase 驗證紀錄](docs/VALIDATION.md)
- [Code Review 紀錄](docs/CODE_REVIEW.md)
- [v0.3 Modern UI 與功能設計](docs/UI_DESIGN.md)

## 開發環境

- JDK 17
- Android SDK 35
- Android Gradle Plugin 8.7.2
- Kotlin 2.0.21
- Gradle Wrapper 8.9

建立本機 SDK 設定：

```powershell
Copy-Item local.properties.example local.properties
```

請依電腦環境調整 `local.properties` 的 `sdk.dir`，然後執行：

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

Debug APK 會產生於：

```text
app/build/outputs/apk/debug/app-debug.apk
```

若要產生不內嵌本機 API 設定的分享用測試 APK，請執行：

```powershell
.\gradlew.bat assembleDebug -PstripEmbeddedSecrets=true
```

## 目前功能

### 功能全貌

| 功能面 | 使用者可以完成的事 | 狀態 |
| --- | --- | --- |
| 總覽與介面 | 查看最近量測、裝置狀態與趨勢，切換淺色／深色外觀 | 可用 |
| 裝置連線 | 掃描、選擇及管理 myAir S1，並在背景維持連線 | 可用 |
| 量測與同步 | 手動量測、解析結果、同步時間及讀取裝置資訊 | 可用 |
| 排程自動化 | 建立一次性、週期性或每日排程，依序執行量測任務 | 可用 |
| 報告與資料 | 保存 30 天紀錄、檢視圖表、匯出 CSV 或同步後端 | 可用；雲端為選用 |
| 診斷與隱私 | 查看 BLE Console、匯出診斷資料，不蒐集手機定位 | 可用 |

### 總覽與操作介面

- 採用 Material 3 五分頁介面：總覽、連線、量測、報告與裝置。
- 顯示最新量測、本次平均、量測起訖時間與樣本數。
- 依環境部 PM2.5 濃度級距動態調整量測卡片底色；此處僅作瞬時濃度分級參考，不代表完整 AQI。
- 支援跟隨系統、淺色與深色三種顯示模式。

### 裝置連線與管理

- 掃描並連線 myAir S1；短暫收集附近候選裝置，單台時自動連線，多台時顯示選擇清單。
- 多裝置清單顯示裝置名稱、訊號強度及「上次使用」標記。
- 可在「連線」頁更換裝置或忘記上次使用的裝置。
- Foreground Service 在背景維持連線，斷線時採指數退避自動重連。
- 提供可開關的 BLE 斷線提醒通知。

### 量測與裝置同步

- 執行單次量測，解析 PM2.5、溫度、濕度、電量與量測時間。
- 以 2.5 秒未收到新封包作為一次量測結束條件。
- 手動同步感測器時間。
- 讀取 Protocol version、Model name、Software／Firmware version 與 Hardware revision。
- 實驗性提供裝置端歷史量測的唯讀同步；資料確認寫入前後皆不送出清除命令。

### 排程與任務佇列

- 一次性排程：5／15／30 分鐘後，或自訂延遲時間。
- 週期性排程：每 15／30／60／120／240 分鐘。
- 每日排程：於指定時間固定執行。
- 手動與排程量測共用 FIFO 任務佇列，畫面顯示目前任務及等待數，避免量測重疊或覆蓋。
- 排程保存在 SQLite；服務或手機重啟後會恢復下一次排程。
- 斷線超過 10 分鐘寬限時，該次任務記錄為「未執行」，不無限累積補做。

### 報告、儲存與後端整合

- 將量測結果與 BLE 狀態保存在 App 私有 SQLite 資料庫，僅保留最近 30 天資料。
- 顯示當日量測 session 與近 30 日每日平均折線圖。
- 可透過 Android 文件選擇器匯出單日或近 30 日 CSV。
- 內建 SQLite transactional outbox、HTTPS 認證及失敗重試機制。
- 可選用 Supabase Edge Functions，上傳量測資料並提供受保護的最新量測唯讀 API。

### 診斷與隱私

- 提供可獨立捲動的 BLE 診斷 Console。
- 可透過 Android 文件選擇器匯出 JSON 診斷資料。
- Android 12 以上使用 Nearby devices 權限，不蒐集手機定位；GPS 功能僅保留未來擴充空間。

## 手機測試

1. 關閉其他可能正在連線感測器的 App。
2. 安裝本專案產生的 APK。
3. 允許「附近裝置」權限。
4. 按「掃描並連線」。
5. 連線完成後按「立即量測」。
6. 如需回報問題，按「匯出診斷」並先確認內容不含不希望分享的裝置資訊。

## 韌體與疑難排解

台灣大哥大未公開提供本裝置的獨立韌體下載檔或手動更新工具；既有智慧家庭系統也已不再維護此裝置。因此，本專案沒有設計韌體下載、檢查、更新或刷寫功能。

若遇到藍牙配對或數值同步異常，建議依序嘗試：

1. 關閉再開啟手機藍牙。
2. 將 myAir S1 偵測器重新開機後再連線。
3. 仍無法恢復時，重新安裝 App 後再嘗試。

重新安裝 App 會清除其私有 SQLite 量測資料、尚未送出的 outbox 與已保存的偏好裝置；如需保留診斷內容，請先使用「匯出診斷」。

## 可擴充範圍

目前程式已提供 BLE 連線、FIFO 量測佇列、本機資料庫、transactional outbox 與受保護的唯讀 API 等基礎元件。下列項目尚未實作，也不是既定版本承諾；開源社群可依自己的 IoT、Smart Home 或研究情境選擇擴充。

| 擴充方向 | 可採用的既有基礎 | 建議改造內容 |
| --- | --- | --- |
| Home Assistant 整合 | 最新量測唯讀 API、outbox | 建立 sensor integration、MQTT bridge 或 RESTful sensor |
| Node-RED 自動化 | HTTPS API、結構化量測資料 | 提供 flow 範例、告警條件與資料轉送節點 |
| 裝置與空氣品質 Dashboard | 最新量測 API、30 天本機資料模型 | 建立網頁儀表板、長期趨勢與多裝置檢視 |
| 遠端量測觸發（Touch）API | `REMOTE_COMMAND` 來源、FIFO 任務佇列 | 建立受保護的命令佇列、手機接收機制與執行回報 |
| 站點與民間空氣地圖 | 後端 `metadata` 擴充欄位 | 加入使用者自訂站點，或另行設計明確啟用的 GPS 定位模式 |

### 遠端量測觸發

- 可由 Supabase、其他自架 API 或自動化服務派發量測命令，再交由手機既有 FIFO 佇列執行。
- 擴充時應加入身分驗證、命令到期時間、冪等識別、重試上限與執行狀態回報，避免重複觸發或離線命令無限堆積。
- 手機仍需能連線至感測器；遠端 API 只負責派發任務，不能取代 BLE 實體連線。

### 站點、地圖與 GPS

- 可先採用使用者手動設定站點的方式，讓資料能對應民間空氣地圖，而不要求定位權限。
- 社群若需要 GPS，可另行加入明確啟用、可隨時停用的定位模式，並自行設計座標保存、精度降階、上傳同意與刪除機制。
- 現行版本不讀取、保存或上傳 GPS 座標；既有 API 也不接受位置資料。擴充者不應將定位視為預設量測行為。

### Smart Home 與視覺化

- Home Assistant、Node-RED 與自製 Dashboard 可直接從受保護的最新量測 API 起步，也可另建 bridge 將資料轉為 MQTT 或其他協定。
- 若要保存超過 30 天的趨勢或管理多台裝置，建議交由外部資料庫負責，避免擴張手機端 SQLite 的既有保留策略。
- 新增整合時不應把 API 金鑰、裝置識別資訊或私人站點資料提交至 Repository。

## 資料與隱私

- 預設不連線任何雲端服務。
- 只有在本機明確填入 API URL 與金鑰後才會同步。
- 資料只保存在 App 私有儲存空間。
- 不讀取或保存 GPS 座標。
- 本機量測、session、事件與 outbox 最長保留 30 天。
- 診斷資料只在使用者主動匯出時產生。
- Repository 不包含實機 MAC、裝置名稱、手機型號、主機名稱、私有 IP、憑證或使用者量測資料。

## License

本專案採用 [MIT License](LICENSE)。
