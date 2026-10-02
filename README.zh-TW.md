# ES Logger

[![Build](https://github.com/cymetrics/burp-es-logger/actions/workflows/build.yml/badge.svg)](https://github.com/cymetrics/burp-es-logger/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

把 Burp 經手的每一筆 HTTP / WebSocket 流量記錄到 Elasticsearch 的擴充，
並以 SHA-256 hash chain 讓「刪改」留下痕跡。

為授權滲透測試而寫 —— 「測試者當時究竟送了什麼、何時送的」這個問題，
幾個月後仍要能回答，而且是回答給當時不在場的人。

> English: [README.md](README.md)

## 做什麼

- **涵蓋所有工具。** 掛在 `api.http()`，所以 Proxy、Repeater、Intruder、Scanner
  以及其他 extension 的流量都收得到，不只是瀏覽器經過 proxy 的部分。
- **用 `messageId` 配對請求與回應。** 沒等到回應的請求，逾時後仍以 `request_only` 落檔，
  不會無聲消失。
- **每筆都入鏈。** 文件帶有 `raw_sha256` 與 `body_sha256`，再加上
  `record_sha256 = SHA256(material ‖ prev_hash)`。在中間刪除、插入或竄改任何一筆，鏈就會斷。
- **絕不重複。** `_bulk` 用 `create` 搭配自訂 `_id`，網路中斷後重送會得到 409，視為已存在。
- **不干擾 Burp。** 擷取回呼只取位元組就交給背景執行緒；上傳走 JDK 原生 `HttpClient`，
  不經過 Burp，所以擴充不會記錄自己的流量，API key 也不會進到 proxy history。

## 設計取捨

這些都是刻意的決定，實際用在案子上之前請先看過。

**本地暫存不是封存。** 預設完全不寫磁碟：待上傳的紀錄放在 32 MB 的記憶體佇列裡，
Elasticsearch 一確認就刪除。Burp 關閉或積壓超過上限時，未送出的會遺失 —— 而因為 `seq`
持續遞增，缺口是**看得見的**，不會偽裝成完整紀錄。想用磁碟換完整性，就打開
「待上傳佇列落地到 SQLite」。

**Elasticsearch 是唯一的稽核來源。** hash chain 證明的是「現有的沒有被改過」，
不是「沒有東西不見」。請搭配 append-only 的 API key（見
[`elasticsearch/setup.md`](elasticsearch/setup.md)），讓測試機即使被入侵也改不了已上傳的紀錄。

**極速模式只省被排除靜態資源的 body 雜湊。** 圖片、字型、CSS 是客戶自己的資源，
本來就不會保存 body，額外那次 `body_sha256` 換不到舉證力卻實際花時間。
`raw_sha256` 一律計算，所以整包訊息（標頭、狀態列、body 位元組）仍然可驗證，
只是少了單獨的 body 摘要，紀錄會標記 `hashes_skipped: true`。想要每個摘要都無條件計算就關掉它。

**ES 永遠不會接受的紀錄最終會被放棄。** 伺服器錯誤、流量限制與認證失敗都會無限重試 ——
那是伺服器或金鑰的問題，資料本身沒錯。但若是文件自身的問題（例如 mapping 衝突），
不處理就會永遠卡住它後面的每一筆。這種批次會反覆對半切以逼近出問題的那一筆，
該筆三次之後放棄。放棄時會在 Burp 的 Extensions log 明確寫出是哪幾個 seq 與原因，
分頁上也有持續可見的計數，並留下 `seq` 缺號作為證據。

**body 會被過濾，但指紋不會。** 被排除或截斷的 body 仍會記下 `body_len`，
以及（極速模式未套用時）`body_sha256` —— 足以證明某個特定內容曾經通過，而不必保存它。

## 自行編譯

需要 JDK 17，Gradle wrapper 已附在 repo 內。

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew shadowJar
# → build/libs/burp-es-logger.jar
```

jar 內含 macOS、Windows、Linux（含 musl）的 SQLite 原生函式庫，同一個檔案在各平台都能用。

## 安裝

到 [Releases](https://github.com/cymetrics/burp-es-logger/releases) 下載最新的
`burp-es-logger.jar`，或自行編譯（見下一節）。每個 release 的 jar 旁邊都附
`.sha256`，載入 Burp 之前請先驗證：

```bash
shasum -a 256 -c burp-es-logger.jar.sha256
```

Burp → Extensions → Add → Extension type 選 **Java** → 選那個 jar，會多出 **ES Logger** 分頁。

打算反覆重編的話，把該 extension 的 **Auto-reload** 打開，Burp 會自己換上新的 jar。

## Elasticsearch 設定

照 [`elasticsearch/setup.md`](elasticsearch/setup.md) 做：建立 index template，
並產生一把只能新增、不能讀 / 改 / 刪的 API key。
[`elasticsearch/dev-tools.txt`](elasticsearch/dev-tools.txt) 是同樣的內容，可直接貼進
Kibana 的 Dev Tools。

接著在分頁填入 endpoint、API key、index 前綴、Tester ID、Project ID →
**儲存設定** → **測試連線**。

## 設定項目

| 項目 | 預設 | 說明 |
|---|---|---|
| Elasticsearch Endpoint | — | 完整網址，結尾不要斜線 |
| API Key (encoded) | — | 只需要 `burp-log-*` 的 `create_doc` 權限 |
| Index 前綴 | `burp-log` | 實際 index 為 `<前綴>-<project id>` |
| Tester / Project ID | — | 寫入每一筆文件；project ID 同時決定 index 名稱 |
| 排除 body 的副檔名 | `js,gif,jpg,jpeg,png,ico,css,woff,woff2,ttf,svg` | 同時比對副檔名與 Content-Type |
| 單筆 body 上限 | 2 MB | 超過的只留截斷片段 + 完整長度 + 完整 hash |
| 無回應逾時 | 120 秒 | 逾時後以 `request_only` 落檔 |
| 儲存 body | 開 | 關閉則所有 body 只留 hash |
| 記錄 WebSocket 訊息 | 開 | |
| 極速模式 | **開** | 被排除的靜態資源連雜湊都不算 |
| 上傳間隔 | 15 秒 | 只是閒置輪詢；有積壓會連續送 |
| 每批筆數 | 500 | 另有單次 `_bulk` 8 MB 的上限 |
| 啟用自動上傳 | 開 | |
| 落地到 SQLite | **關** | 開啟＝用磁碟換取跨重啟的完整性 |

## 文件結構

```jsonc
{
  "@timestamp": "2026-10-02T07:49:05.323Z",
  "seq": 1234, "doc_id": "uuid", "session_id": "uuid",
  "tester_id": "zet", "project_id": "acme2026", "capture_host": "laptop",
  "type": "http", "tool": "Proxy",
  "request":  { "time", "method", "url", "host", "port", "secure", "headers",
                "body" | "body_b64", "body_stored", "body_len", "body_sha256",
                "body_truncated", "body_skip_reason", "raw_sha256", "hashes_skipped" },
  "response": { "time", "status", "headers", "body" | "body_b64", "...": "同 request" },
  "integrity": { "algo": "sha256", "scheme": "...", "prev_hash": "...", "record_sha256": "..." }
}
```

`type` 為 `http`、`http_request_only`、`http_response_only`、`websocket` 其中之一。

## 驗證 hash chain

依 `seq` 排序後逐筆重算。每個欄位前置它自己的 UTF-8 位元組長度，
因此任何欄位內容都無法偽造欄位邊界：

```
fields = [ seq, doc_id, type,
           session_id, tester_id, project_id, capture_host,
           request.time, response.time,
           tool, request.method, request.url, response.status,
           request.raw_sha256, request.body_sha256,
           response.raw_sha256, response.body_sha256 ]

material = prev_hash  ‖  對每個欄位：len(UTF-8 位元組) ‖ ":" ‖ 欄位內容

record_sha256 = SHA256(material)
```

第一筆的 `prev_hash` 是 64 個 0；`prev_hash` 放在最前面是因為它固定 64 個十六進位字元，
不會與後面的長度前綴混淆。缺少的欄位以空字串參與計算 —— 極速模式跳過的雜湊也是如此。
WebSocket 紀錄的欄位組合略有不同，詳見 `RecordWriter.kt`。

每份文件的 `integrity.scheme` 也記錄了這個公式，單看一份文件就知道該怎麼驗證。

`seq` 出現缺口代表那幾筆從未送達 Elasticsearch；雜湊對不上則代表文件被改過。

## 實際用在案子之前

- 這些 log 含受測方的**帳密、session token 與個資**，而且會離開本機存到第三方雲端。
  請先確認測試合約與 NDA 允許，以及資料落地區域是否有限制。
- Scanner 與 Intruder 一次可能產生數十萬筆。請先估資料量與費用，
  必要時對這兩個工具關閉 body 或調低大小上限。
- API key 以明文存在 Burp 的使用者偏好設定中。請保護測試機，並在結案時撤銷金鑰。

## 已知限制

- API key 尚未接 OS keychain。
- 二進位 body 以 base64 進 ES 會膨脹約 33%，已用大小上限與截斷夾住。
- 文字 body 以 UTF-8 解碼，非法位元組會變成 U+FFFD，存下的文字因此與 `body_sha256` 不一致；
  `raw_sha256` 仍涵蓋原始位元組。
- hash chain 偵測竄改，但不能阻止竄改。不可刪除性來自 append-only 的 API key，
  以及你額外加上的外部存證機制。

## 授權

MIT，見 [LICENSE](LICENSE)。
