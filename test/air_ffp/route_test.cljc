(ns air-ffp.route-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [air-ffp.route :as route]
            [air-ffp.view :as view]))

(deftest dispatch-page-and-health
  (is (= :page (:action (route/dispatch "GET" "/"))))
  (is (= :health (:action (route/dispatch "GET" "/health"))))
  (is (= :method-not-allowed (:action (route/dispatch "POST" "/health"))))
  (is (= :not-found (:action (route/dispatch "GET" "/nope"))))
  (testing "src/app.ts の /_app/meta は持ち越していない（README『持ち越さなかったもの』）"
    (is (= :not-found (:action (route/dispatch "GET" "/_app/meta"))))))

(deftest dispatch-xrpc
  (testing "nsid をそのまま取り出す"
    (is (= {:action :xrpc :nsid "com.etzhayyim.apps.airFfp.enrollMember"}
           (route/dispatch "POST" "/xrpc/com.etzhayyim.apps.airFfp.enrollMember"))))
  (testing "空だけが 400。多段は移行前と同じく転送する（絞るのは方針変更）"
    (is (= :bad-request (:action (route/dispatch "POST" "/xrpc/"))))
    (is (= {:action :xrpc :nsid "a/b"} (route/dispatch "POST" "/xrpc/a/b"))))
  (testing "移行前の SvelteKit route は NSID の prefix を見ていない。ここも見ない"
    (is (= {:action :xrpc :nsid "com.example.other.thing"}
           (route/dispatch "POST" "/xrpc/com.example.other.thing"))))
  (testing "preflight と method"
    (is (= :cors-preflight (:action (route/dispatch "OPTIONS" "/xrpc/x"))))
    (is (= :method-not-allowed (:action (route/dispatch "GET" "/xrpc/x"))))))

(deftest mcp-url-resolution
  (is (= "https://mcp.etzhayyim.com/xrpc/com.etzhayyim.mcp.message"
         (route/mcp-router-url {})))
  (is (= "https://a.example/x" (route/mcp-router-url {:AGENTGATEWAY_MCP_ROUTER_URL "https://a.example/x/"})))
  (testing "空白だけの設定は未設定として扱う"
    (is (= "https://b.example" (route/mcp-router-url {:AGENTGATEWAY_MCP_ROUTER_URL "   "
                                                     :MCP_ROUTER_URL "https://b.example"})))))

(deftest capabilities-come-from-env
  (testing "wrangler.jsonc が実際に渡している形"
    (is (= ["enrollMember" "accruePoints" "redeemReward"]
           (route/declared-capabilities
            "[\"enrollMember\",\"accruePoints\",\"redeemReward\"]"))))
  (testing "未設定・空・読めない形は何も主張しない"
    (is (= [] (route/declared-capabilities nil)))
    (is (= [] (route/declared-capabilities "   ")))
    (is (= [] (route/declared-capabilities "[]")))))

(deftest unwrap
  (is (= {:ok? true :value {:a 1}} (route/unwrap-mcp {:result {:structuredContent {:a 1}}})))
  (is (= {:ok? true :value {:a 1}} (route/unwrap-mcp {:result {:a 1}})))
  (is (false? (:ok? (route/unwrap-mcp {:error {:message "boom"}})))))

(deftest page-shows-the-real-routes-and-the-real-capabilities
  (testing "ページは route 表と env から描く。0 も [] も焼かない（docs/adr/0001 の欠陥）"
    (let [html (view/render {:css "/*x*/" :routes route/routes
                             :vars [:APP_NANOID :APP_UI_TYPE]
                             :capabilities (route/declared-capabilities
                                            "[\"enrollMember\",\"accruePoints\",\"redeemReward\"]")
                             :mcp-url "https://mcp.example/x"})]
      ;; **表のセルとして**出ていることを見る。素の path で探すと `/` は
      ;; あらゆる閉じタグの部分文字列なので、その 1 件は落ちようがない
      ;; ——「表を空にする」mutation を当てた時に /health と /xrpc/:nsid だけが
      ;; 赤くなり `/` が緑のままだったので気づいた（2026-08-18 実測）。
      (doseq [r route/routes]
        (is (str/includes? html (str "<span class=\"ffp-mono\">" (:route/path r) "</span>"))
            (str (:route/path r) " が route 表のセルとして出ていない")))
      (is (str/includes? html "APP_NANOID"))
      (is (str/includes? html "enrollMember"))
      (is (str/includes? html "redeemReward"))
      (is (str/includes? html "https://mcp.example/x"))
      (testing "移行前のページが焼いていた文言が戻っていない"
        (is (not (str/includes? html "No public route is declared")))
        (is (not (str/includes? html "60-apps/etzhayyim-project-air-ffp")))))))

(deftest relay-headers-forwards-what-it-received
  (testing "移行前は host を削るだけで、authorization も上流へ届いていた"
    (let [h (route/relay-headers [["Host" "x.example"]
                                  ["Authorization" "Bearer t"]
                                  ["Content-Length" "9"]
                                  ["Content-Encoding" "gzip"]
                                  ["X-Trace" "abc"]]
                                 "com.a.b")]
      (is (= "Bearer t" (get h "authorization"))
          "authorization が落ちている —— preflight はこれを許可すると言っている")
      (is (= "abc" (get h "x-trace"))
          "呼び手が付けた header が落ちている")
      (is (nil? (get h "host")) "host は宛先が変わるので渡さない")
      (is (nil? (get h "content-length")) "body を詰め直すので元の長さは嘘になる")
      (is (nil? (get h "content-encoding")) "body を詰め直すので元の encoding も嘘になる")
      (is (= "application/json" (get h "content-type")))
      (is (= "com.a.b" (get h "x-etzhayyim-xrpc-method")))
      (is (= "cljs-worker" (get h "x-etzhayyim-bff"))))))
