# 裝置資料格式

本專案以公開運作行為、實機互通測試與資料一致性驗證建立獨立的裝置識別方式。

## BLE services

| 用途 | UUID |
|---|---|
| Measurement service | `46494854-4443-5365-7276-696365030000` |
| Control point | `46494854-4443-5365-7276-696365030001` |
| Sensor measurement | `46494854-4443-5365-7276-696365030002` |
| Sync measurement | `46494854-4443-5365-7276-696365030003` |

## Measurement frame

Sensor measurement notification 為 18 bytes：

| Offset | Size | 欄位 |
|---:|---:|---|
| 0 | 2 | Sequence |
| 2 | 4 | Device time，little-endian |
| 6 | 2 | Timezone |
| 8 | 1 | Protocol version |
| 9 | 1 | Trigger |
| 10 | 1 | Battery percent |
| 11 | 2 | PM2.5，little-endian |
| 13 | 1 | Cover state |
| 14 | 2 | Temperature × 10，little-endian |
| 16 | 2 | Humidity × 10，little-endian |

若裝置時間與接收時間相差超過七天，客戶端會保留原始值，但將 `timestampUtc` 標為未同步，避免錯誤時間進入自動化流程。

## Export schema

```json
{
  "schema_version": 2,
  "measurements": [
    {
      "received_at": 0,
      "device_epoch_seconds": 0,
      "pm25_ug_m3": 0,
      "temperature_c": 0.0,
      "humidity_pct": 0.0,
      "battery_pct": 0,
      "trigger": "APP",
      "raw_hex": "REDACTED_EXAMPLE"
    }
  ],
  "events": []
}
```

公開 issue 前請檢查診斷資料；事件可能包含使用者當下的裝置廣播名稱或其他環境資訊。

## Measurement session payload

一次量測以最後一筆 BLE 感測封包後 2.5 秒沒有新封包作為結束。平均值使用該區間內的全部樣本；`started_at`、`ended_at` 為 Unix epoch milliseconds。

```json
{
  "schema_version": 1,
  "event_id": "00000000-0000-0000-0000-000000000000",
  "device_id": "myair-s1",
  "started_at": 0,
  "ended_at": 0,
  "sample_count": 1,
  "latest": {
    "received_at": 0,
    "pm25_ug_m3": 0,
    "temperature_c": 0.0,
    "humidity_pct": 0.0,
    "battery_pct": 0,
    "trigger": "APP"
  },
  "average": {
    "pm25_ug_m3": 0.0,
    "temperature_c": 0.0,
    "humidity_pct": 0.0
  }
}
```

此 payload 不含 GPS、經緯度或可推定位置的欄位；未來地圖 adapter 將是另行啟用的擴充。
