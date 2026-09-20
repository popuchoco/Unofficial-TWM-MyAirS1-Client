# 安全與隱私

## 現行版本

- 不包含任何預設雲端端點或憑證。
- 不蒐集 GPS 座標。
- SQLite 位於 App 私有空間。
- 只有使用者主動操作時才匯出 JSON。
- 不執行韌體更新、校正覆寫或原廠帳號操作。

## 公開 Repository 邊界

不得提交：

- 實機藍牙位址、廣播名稱或序號。
- 手機型號、主機名稱、使用者名稱及絕對本機路徑。
- 私有 IP、VPN 位址、API URL、token、keystore 或密碼。
- 真實量測與診斷匯出。
- APK、AAB、build outputs 或本機研究素材。

## 未來同步服務

- 預設採手機主動上傳，不在手機開放常駐 HTTP listener。
- 使用 TLS 或私人 overlay network。
- upload token 與 Agent read token 分離且可撤銷。
- 支援 idempotency，避免網路重試造成重複資料。
- 敏感設定使用 Android Keystore 保護，不寫入原始碼或一般偏好檔。

安全問題請使用 GitHub Security Advisory 私下回報，不要在公開 issue 附上未遮蔽的診斷 JSON。
