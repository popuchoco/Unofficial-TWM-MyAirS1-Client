# Unofficial TWM myAir S1 Client

一套為台灣大哥大 myAir S1 空氣品質感測器設計的非官方 Android 客戶端。

原廠 App 已停止維運，但既有感測器仍可繼續服務。這個專案以 IoT、Smart Home 與個人資料自主為出發點，建立全新的 BLE 資料識別、保存與同步流程，讓使用者能在手機上讀取感測資料，並逐步與 Home Assistant、Node-RED、自架 API 或其他自動化 Agent 整合。

> 本專案與台灣大哥大及原廠 App 開發者無關；產品名稱與商標分屬其權利人所有。

## 裝置規格摘要

- PM2.5 量測範圍：`0–500 µg/m³`。
- 無線連線：Bluetooth Low Energy 4.2。
- BLE 工作頻率：`2402–2480 MHz`。

## 目前功能

- 掃描並連線 myAir S1。
- 執行單次量測。
- 解析 PM2.5、溫度、濕度、電量與量測時間。
- 將量測及 BLE 狀態保存在 App 私有 SQLite 資料庫。
- 提供可獨立捲動的 BLE 診斷 Console。
- 透過 Android 文件選擇器匯出 JSON 診斷資料。
- 手動同步感測器時間。
- 以 2.5 秒無新封包作為一次量測結束，顯示最新值、本次平均、量測起訖時間與樣本數。
- 依環境部 PM2.5 濃度級距動態調整量測卡片底色（僅作瞬時濃度分級參考，非完整 AQI）。
- Foreground Service、偏好裝置與斷線指數退避自動重連。
- SQLite transactional outbox、HTTPS 認證、重試與 30 天本機資料留存。
- 可選用 Supabase Edge Functions 提供受保護的上傳及最新量測唯讀 API。
- Material 3 五分頁介面，提供總覽、連線、量測、報告與裝置資訊。
- 今日 session 與近 30 日每日平均折線圖，以及單日／近 30 日 CSV 匯出。
- 可切換跟隨系統、淺色與深色模式。
- 可開關的 BLE 斷線提醒通知。
- 讀取裝置 Protocol、Model、Software/Firmware 與 Hardware revision。
- Android 12 以上使用 Nearby devices 權限，不蒐集手機定位。

## 專案狀態

目前是可安裝與實機測量的早期測試版本。基礎 BLE 連線、服務探索、通知訂閱、量測命令與資料解析已通過實機驗證。

後續規劃：

- 裝置歷史資料同步。
- 可設定的定時量測。
- Home Assistant／Node-RED adapter。
- 使用者主動設定的站點／民間空氣地圖 adapter（本版不實作 GPS）。

詳細進度見 [Roadmap](docs/ROADMAP.md)。

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
