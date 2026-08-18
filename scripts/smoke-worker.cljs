#!/usr/bin/env nbb
;; smoke-worker — 実際にビルドされた bundle を import して叩く。
;;
;; ここが「deploy される成果物」に触る唯一の検査である。テスト
;; (test/air_ffp/route_test.cljc) はソースの判断を固定するが、bundle が
;; 本当に Worker の形で答えるかは言えない —— export の形、shadow の
;; :advanced-optimization、`shadow.resource/inline` で焼いた CSS は、
;; どれもビルドを通って初めて存在する。
;;
;; Usage:  nbb scripts/smoke-worker.cljs [<dist/worker.js>]
;; Exit:   0 全て期待どおり · 1 期待と違う · 2 判定できなかった（bundle が無い等）

(require '["node:fs" :as fs] '["node:path" :as path] '["node:url" :as url]
         '[clojure.string :as str])

(def bundle
  "ESM の import は相対パスを package 名と読むので、必ず絶対パスに直してから
  file:// URL にする（`dist/worker.js` をそのまま渡すと『Cannot find package dist』
  になる。実測）。"
  (let [a (first (remove #(str/starts-with? % "--") *command-line-args*))]
    (.resolve path (or a "dist/worker.js"))))

(def failures (atom []))
(defn check! [label expected actual]
  (let [ok (= expected actual)]
    (println (str (if ok "PASS" "FAIL") "\t" label "\texpected=" (pr-str expected) "\tactual=" (pr-str actual)))
    (when-not ok (swap! failures conj label))))

(when-not (.existsSync fs bundle)
  (println (str "UNDETERMINED\tno bundle at " bundle))
  (println "Refusing to report a pass: build it first (see docs/operator-quickstart.md S4).")
  (js/process.exit 2))

;; ---------------------------------------------------------------------------
;; 印は 3 つ。**「出さない」と「出す」を別々に見る。**
;;
;; 片方だけだと「全部隠す」実装も「全部出す」実装も通ってしまう。実測
;; 2026-08-18（app-ongakuka）: ページが『キー名のみ。値は出さない』と書きながら
;; 中継先の値を出しており、sentinel が表示されない var に付いていたので、実際に
;; 出ている唯一の値を検査できていなかった。
;;
;; 実在しそうな値（"yoro" 等）は使わない —— 他の文言と偶然一致しうるし、引用符
;; ごと探すと renderer が " を &quot; に escape するので**決して一致しない**
;; （＝落ちようがない検査になる）。
;; ---------------------------------------------------------------------------

(def hidden-sentinel
  "**出てはいけない値。** APP_UI_TYPE の値に置く（ページはこの var の値を描かない）。"
  "SENTINEL-HIDDEN-7c4e91")

(def shown-router
  "**出なければいけない値 その 1。** 中継先はページに値そのものが出る。
  `.invalid` は RFC 2606 で必ず解決しないので、出ていることを実 DNS に依存せず
  確かめられる（かつ下の 502 検査も実ネットワークに依存しない）。"
  "https://mcp.example.invalid/xrpc/probe")

(def shown-capability
  "**出なければいけない値 その 2。** APP_CAPABILITIES の中身。ページがこれを
  出すということは、capability 一覧を**焼いていない**ということである
  —— docs/adr/0001 が記録した『ページに焼いた値が設定と食い違う』欠陥の逆。"
  "CAPSENTINEL-b91d0f")

(def env #js {"APP_NANOID" "a1rffp01"
              "APP_UI_TYPE" hidden-sentinel
              "APP_CAPABILITIES" (str "[\"" shown-capability "\"]")
              "AGENTGATEWAY_MCP_ROUTER_URL" shown-router})

(defn- call [h method path]
  (let [req (js/Request. (str "https://air-ffp.etzhayyim.com" path) #js {:method method})]
    (-> (js/Promise.resolve ((.-fetch h) req env #js {}))
        (.then (fn [res] (-> (.text res)
                             (.then (fn [body] {:status (.-status res)
                                                :ct (.get (.-headers res) "content-type")
                                                :body body}))))))))

(-> (js/import (.-href (.pathToFileURL url bundle)))
    (.then
     (fn [m]
       (let [h (.-default m)]
         (check! "default export has fetch" true (fn? (.-fetch h)))
         (-> (js/Promise.all
              #js [(call h "GET" "/") (call h "GET" "/health")
                   (call h "POST" "/xrpc/") (call h "OPTIONS" "/xrpc/x")
                   (call h "GET" "/nope") (call h "POST" "/health")
                   (call h "GET" "/_app/meta")
                   (call h "POST" "/xrpc/com.etzhayyim.apps.airFfp.enrollMember")
                   (call h "POST" "/xrpc/a/b")])
             (.then
              (fn [[page health bad pre nf mna meta one multi]]
                (check! "GET / status" 200 (:status page))
                (check! "GET / is html" true (str/includes? (or (:ct page) "") "text/html"))
                ;; ページは route 表から描かれる。表の path が **セルとして** 出ていること。
                ;; 素の path で探すと `/` はあらゆる閉じタグの部分文字列なので落ちない。
                (doseq [p ["/" "/health" "/xrpc/:nsid"]]
                  (check! (str "page advertises " p " as a table cell") true
                          (str/includes? (:body page) (str "<span class=\"ffp-mono\">" p "</span>"))))
                ;; env のキーは出す
                (check! "page shows a var key" true (str/includes? (:body page) "APP_NANOID"))
                ;; --- 3 つの印 ---
                (check! "page hides other var values" false (str/includes? (:body page) hidden-sentinel))
                (check! "page shows the relay target it uses" true (str/includes? (:body page) shown-router))
                (check! "page shows capabilities READ FROM env, not baked" true
                        (str/includes? (:body page) shown-capability))
                ;; 焼かれていた古い literal が bundle から出てこないこと
                (check! "the baked pre-migration literals are gone" false
                        (or (str/includes? (:body page) "No public route is declared")
                            (str/includes? (:body page) "60-apps/etzhayyim-project-air-ffp")))
                ;; --- DDS の 2 本。**別の主張なので別の検査。** ---
                ;; 実測（このページ、2026-08-18）: `dads-table` は css 込み 74 /
                ;; css 無し **6**（0 にならない）。`--color-primitive-blue` は 45 / **0**。
                (check! "page uses the design system components" true
                        (str/includes? (:body page) "class=\"dads-table\""))
                (check! "page carries the stylesheet itself" true
                        (str/includes? (:body page) "--color-primitive-blue"))
                ;; --- route ---
                (check! "GET /health status" 200 (:status health))
                (check! "health names its routes" true (str/includes? (:body health) "/xrpc/:nsid"))
                (check! "POST /xrpc/ status" 400 (:status bad))
                (check! "OPTIONS preflight" 204 (:status pre))
                (check! "unknown path" 404 (:status nf))
                (check! "wrong method" 405 (:status mna))
                ;; src/app.ts の /_app/meta は持ち越していない（README「持ち越さなかったもの」）
                (check! "/_app/meta was not carried over" 404 (:status meta))
                ;; 多段パスは単一セグメントと**同一に**扱う（移行前の [...path] と同じ）。
                ;; 中継先が .invalid なので、この比較は実 DNS に依存しない。
                (check! "multi-segment xrpc is relayed exactly like a single segment" true
                        (= (:status one) (:status multi) 502))
                (check! "an unreachable relay is not hidden behind a 200" true
                        (and (str/includes? (:body multi) "MCP router unreachable")
                             (str/includes? (:body multi) shown-router)))
                (let [f @failures]
                  (if (seq f)
                    (do (println (str "FAILED\t" (count f) " check(s): " (str/join ", " f)))
                        (js/process.exit 1))
                    (do (println "OK\tthe built bundle answers as the route table says")
                        (js/process.exit 0))))))))))
    (.catch (fn [e]
              (println (str "UNDETERMINED\tcould not exercise the bundle: " (.-message e)))
              (js/process.exit 2))))
