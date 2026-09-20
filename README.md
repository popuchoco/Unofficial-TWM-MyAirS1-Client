# Unofficial TWM myAir S1 Client

一套為台灣大哥大 myAir S1 空氣品質感測器設計的非官方 Android 客戶端。

原廠 App 已停止維運，但既有感測器仍可繼續服務。這個專案以 IoT、Smart Home 與個人資料自主為出發點，建立全新的 BLE 資料識別、保存與同步流程，讓使用者能在手機上讀取感測資料，並逐步與 Home Assistant、Node-RED、自架 API 或其他自動化 Agent 整合。

> 本專案與台灣大哥大及原廠 App 開發者無關；產品名稱與商標分屬其權利人所有。

## 目前功能

- 掃描並連線 myAir S1。
- 執行單次量測。
- 解析 PM2.5、溫度、濕度、電量與量測時間。
- 將量測及 BLE 狀態保存在 App 私有 SQLite 資料庫。
- 提供可獨立捲動的 BLE 診斷 Console。
- 透過 Android 文件選擇器匯出 JSON 診斷資料。
- 手動同步感測器時間。
- Android 12 以上使用 Nearby devices 權限，不蒐集手機定位。

## 專案狀態

目前是可安裝與實機測量的早期測試版本。基礎 BLE 連線、服務探索、通知訂閱、量測命令與資料解析已通過實機驗證。

尚未完成：

- 背景 Foreground Service 與自動重連。
- 裝置歷史資料同步。
- 可設定的定時量測。
- 帶有 outbox、認證與重試機制的自架 API 同步。
- Home Assistant／Node-RED adapter。

詳細進度見 [Roadmap](docs/ROADMAP.md)。

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
    └── planned secure outbox
             │ HTTPS / private network
             ▼
       Self-hosted API / Smart Home / Agent
```

- [架構文件](docs/ARCHITECTURE.md)
- [軟體設計文件](docs/SOFTWARE_DESIGN.md)
- [資料格式](docs/DATA_FORMAT.md)
- [安全與隱私](docs/SECURITY.md)

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

## 資料與隱私

- 預設不連線任何雲端服務。
- 資料只保存在 App 私有儲存空間。
- 不讀取或保存 GPS 座標。
- 診斷資料只在使用者主動匯出時產生。
- Repository 不包含實機 MAC、裝置名稱、手機型號、主機名稱、私有 IP、憑證或使用者量測資料。

## License

本專案採用 [MIT License](LICENSE)。
