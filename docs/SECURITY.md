# 安全與隱私

## 現行版本

- 不包含任何預設雲端端點或憑證。
- 不呼叫定位 API，不蒐集、不保存也不上傳 GPS 座標。
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

## 可選同步服務

- 預設採手機主動上傳，不在手機開放常駐 HTTP listener。
- 使用 TLS 或私人 overlay network。
- upload token 與 Agent read token 分離且可撤銷。
- 支援 idempotency，避免網路重試造成重複資料。
- 公開原始碼及 CI build 不含端點或金鑰；本機設定不得提交。
- Supabase 資料表啟用 RLS 且不開放 anon policy，僅 Edge Functions 的 service role 可存取。
- v0.2 ingestion 固定忽略用戶端 metadata，避免日後擴充欄位被拿來偷渡位置資料。

未來站點或民間空氣地圖整合必須採明確啟用、由使用者自行指定站點；不把 GPS 蒐集視為既有同步功能的一部分。

安全問題請使用 GitHub Security Advisory 私下回報，不要在公開 issue 附上未遮蔽的診斷 JSON。
