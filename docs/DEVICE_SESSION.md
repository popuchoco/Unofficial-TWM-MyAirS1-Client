# 裝置連線階段

## 多裝置掃描策略（v0.3.5）

- 找到第一個名稱或 Measurement Service 相符的候選後，再固定收集 3 秒並依 BLE 位址去重。
- 單一候選自動連線；多個候選顯示名稱、RSSI 與上次使用標記，等待使用者選擇。
- 更換裝置期間抑制背景重連舊偏好；忘記裝置會清除偏好並停止背景連線。

單次量測採用以下 GATT session：

1. 掃描名稱或 Measurement Service 相符的 BLE peripheral。
2. 建立 GATT 連線。
3. 探索 services 與 characteristics。
4. 啟用 Sensor Measurement notification。
5. 啟用 Control Point notification。
6. 送出量測請求。
7. 接收 control acknowledgement。
8. 接收一組感測資料串流並寫入 SQLite。

GATT 操作必須序列化；Android 同時執行多個 descriptor／characteristic write 容易造成操作遺失。`BleManager` 使用 FIFO queue，在 callback 完成後才啟動下一個操作。

## 相容性策略

- 掃描不強制要求廣播封包包含完整 128-bit Service UUID，也接受相容的裝置名稱。
- 原始 notification 會保留在私人診斷資料中。
- 不自動執行韌體更新、校正覆寫或帳號綁定。
- 時間同步必須由使用者在 UI 主動觸發。
- 不假設 Android bond 等同於 App 內的偏好裝置；兩者分開管理。
