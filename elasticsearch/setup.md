# Elasticsearch Serverless 設定步驟

以下指令用「管理權限」的 key（或 Kibana Dev Tools）執行一次即可。
之後 Burp 端用產生的**最小權限 API key**，只能新增、不能讀 / 改 / 刪。

設定變數：

```bash
ES="https://<你的-serverless-endpoint>"      # 不要結尾斜線
ADMIN="ApiKey <管理用的-encoded-key>"          # 或改用 Kibana Dev Tools 直接貼 JSON
```

## 1. 建立 index template

```bash
curl -s -X PUT "$ES/_index_template/burp-log" \
  -H "Authorization: $ADMIN" \
  -H "Content-Type: application/json" \
  --data-binary @index-template.json
```

## 2. 建立最小權限 API key（Burp 端用這把）

> **這步必須以「使用者身分」執行，不能用 API key 認證。**
> ES 不允許 derived key（用 API key 認證所建立的 key）帶自訂權限，
> `role_descriptors` 只能是空的（空＝完整繼承 parent 權限）。
> 用 API key 打下面這支會得到：
> `creating derived api keys requires an explicit role descriptor that is empty`
> → 改用 **Kibana Dev Tools**（以登入身分執行）貼 `api-key.json` 的內容：
>
> ```
> POST /_security/api_key
> { ...api-key.json 全文... }
> ```
>
> 或 Kibana → Project settings → Management → API keys → Create API key →
> 打開 **Control security privileges**，貼入 `api-key.json` 裡 `role_descriptors` 的值。

（若你是用 basic auth 的自管叢集，才能走 curl：）

```bash
curl -s -X POST "$ES/_security/api_key" \
  -u "<user>:<pass>" \
  -H "Content-Type: application/json" \
  --data-binary @api-key.json
```

回應會長這樣：

```json
{ "id": "xxx", "name": "burp-logger-writer", "api_key": "yyy", "encoded": "ZXh...=" }
```

把 `encoded` 整串貼到 Burp「ES Logger」分頁的 **API Key** 欄位。
（Burp 端送出的標頭是 `Authorization: ApiKey <encoded>`。）

> 若你拿到的是分開的 `id` 和 `api_key`，encoded = `base64("<id>:<api_key>")`。

## 2.5 模板改版後，既有 index 要另外補

index template **只套用到之後新建的 index**。如果你已經在寫某個 index，
新增的欄位要直接對它 PUT 一次 mapping（新增欄位是允許的，改既有欄位型別則不行）：

```bash
curl -s -X PUT "$ES/burp-log-<project_id>/_mapping" \
  -H "Authorization: ApiKey $ADMIN_KEY" \
  -H "Content-Type: application/json" \
  -d '{"properties":{
        "request":{"properties":{"hashes_skipped":{"type":"boolean"}}},
        "response":{"properties":{"hashes_skipped":{"type":"boolean"}}},
        "websocket":{"properties":{"hashes_skipped":{"type":"boolean"}}}
      }}'
```

沒補的話，`hashes_skipped` 仍然會存在文件的 `_source` 裡（mapping 是 `dynamic: false`，
不會報錯），但**無法用來搜尋或聚合** —— 也就是查不出「哪些訊息沒有雜湊」。

## 3. 權限說明

- `create_doc`：只能用 `op_type=create` 新增文件，**不能覆寫、刪除或讀取**。
  → 即使測試機被入侵，攻擊者也無法竄改或清掉已上傳的紀錄。
- `auto_configure`：第一次寫入時自動依 template 建立 index / 套用 mapping。
- 想更嚴格可拿掉 `auto_configure`，改由管理端先手動建好 index。

## 4. index 命名與保留

- 實際 index = `<前綴>-<project_id>`，例如 `burp-log-acme2025`（自動轉小寫、去除非法字元）。
- 這是一般 index（非 data stream），採 client 端自訂 `_id` 以確保**重送不重複**。
- 稽核用途通常要「保留到結案」，所以預設不自動刪除。
  要分月輪替可把 Project ID 設成含月份，或在管理端定期搬移 / 刪除舊 index。

## 5. 驗證 hash chain（結案時）

每筆文件有 `integrity.prev_hash` 與 `integrity.record_sha256`。
依 `seq` 排序後，逐筆用下列公式重算並比對即可證明沒有被刪改：

```
material = seq ⟨US⟩ doc_id ⟨US⟩ type ⟨US⟩ request.time ⟨US⟩ response.time ⟨US⟩
           tool ⟨US⟩ method ⟨US⟩ url ⟨US⟩ status ⟨US⟩
           request.raw_sha256 ⟨US⟩ request.body_sha256 ⟨US⟩
           response.raw_sha256 ⟨US⟩ response.body_sha256
record_sha256 = SHA256( material ⟨US⟩ prev_hash )
```

`⟨US⟩` = 0x1F（Unit Separator）。第一筆的 `prev_hash` 為 64 個 0。
（WebSocket 記錄的 material 欄位組合略有不同，詳見 RecordWriter.kt。）
鏈的驗證在 ES 端做：依 `seq` 排序後逐筆重算，`seq` 不連續就代表有缺漏。
本地 SQLite 只是 outbox（上傳成功即刪），不保留副本。
