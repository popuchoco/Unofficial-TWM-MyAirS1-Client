# v0.3 Modern UI 與功能設計

## 導覽架構

手機介面採 Material 3、五個帶圖示的底部導覽分頁：

| 分頁 | 內容 |
|---|---|
| 總覽 | 最近一次結果、session 平均、裝置電量、BLE 連線狀態 |
| 連線 | 掃描並連線、Foreground Service、自動重連、可關閉的斷線提醒 |
| 量測 | 立即量測、同步裝置時間、結果卡、可捲動 Console、診斷匯出 |
| 報告 | 今日 session 折線、近 30 日每日平均折線、CSV 範圍選擇視窗 |
| 裝置 | Protocol、Model、Software/Firmware、Hardware、App 版本及外觀設定 |

## 資料呈現原則

- 總覽在 App 重新開啟後仍從 SQLite 顯示最近一次完成的 session，不要求先重新量測。
- 今日折線的每一點代表一次完成 session 的 PM2.5 平均值，避免把同一次量測的逐秒樣本誤認成多次獨立觀測。
- 近 30 日折線先計算每一天內所有 session 的 PM2.5 平均值，每日一點。
- 圖表依區間中的實際值自動調整縱軸；裝置規格上限 `500 µg/m³` 不作固定視窗上限。
- CSV 保存 session 起訖時間、樣本數、最後值、三項平均值及電量；使用者在彈窗選擇今日或近 30 日。

## 裝置資訊

已知相容裝置以自訂 Device Information service `46494854-4443-5365-7276-696365010000` 的 Version characteristic `...010001` 提供 14-byte 資訊：

- bytes 0–1：Protocol version。
- bytes 2–9：Model name（UTF-8）。
- bytes 10–11：Software／Firmware version。
- bytes 12–13：Hardware revision／開發階段。

相容解析沿用既有客戶端顯示規則；若自訂欄位不存在，再嘗試 Bluetooth SIG Device Information service 的 Firmware Revision characteristic。兩者皆無資料時顯示「裝置未提供」，不推測版本。

## 外觀與無障礙

- 提供跟隨系統、淺色、深色三種模式，選擇保存在 App 私有設定。
- 使用 Material 3 surface、elevated card、NavigationBar 與一致的 icon 語彙。
- PM2.5 色帶同時顯示文字分級，不只依靠顏色傳達狀態。
- 高濃度深色背景改用白字，維持對比。

## 通知

- 背景連線維持低干擾常駐通知。
- 已連線後若意外斷線，使用獨立高優先通知頻道提醒；自動重連仍在背景進行。
- 使用者可在「連線」頁關閉斷線提醒，設定不影響自動重連本身。
