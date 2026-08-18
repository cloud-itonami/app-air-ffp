(ns air-ffp.route
  "Which handler answers a request — as data, decided by a pure function.

  This is `.cljc` and not `.cljs` on purpose. Routing is the part of an edge
  worker that is worth testing, and it is testable here without a browser, a
  build, or a network. `air-ffp.worker` is the only namespace that touches
  Request/Response, and it does nothing this file has not already decided.

  It is also the first thing that should move to `.kotoba` once the ingress
  capability qualifies (`:native-aot`/`:wasm-aot` are pending today —
  ADR-2606290000): a route table is a decision over scalars and strings,
  which is exactly the shape that survives that move."
  (:require [clojure.string :as str]))

(def routes
  "The public surface, as data. The landing page renders THIS, so a route that
  exists and a route the page advertises cannot drift apart — the defect
  docs/adr/0001 recorded was a page carrying `routeCount: 0` and `routes: []`
  as literals beside a `wrangler.jsonc` declaring two route patterns."
  [{:route/path "/"           :route/method :get  :route/kind :page
    :route/doc "この appview の説明ページ"}
   {:route/path "/health"     :route/method :get  :route/kind :json
    :route/doc "生存確認。deploy された面が答えることを外から確かめられる"}
   {:route/path "/xrpc/:nsid" :route/method :post :route/kind :proxy
    :route/doc "XRPC を MCP router へ中継する"}])

(defn- xrpc-nsid
  "`/xrpc/<nsid>` の nsid。**空文字だけが nil**。

  多段パス（`/xrpc/a/b`）も通す。移行前に deploy されていた SvelteKit route は
  rest parameter `[...path]` で受けており、`a/b` をそのまま tool 名として
  転送していた（400 にしていたのは空文字だけ）。ここで 1 セグメントに絞ると
  挙動が変わる —— NSID に `/` は現れないので上流で失敗するだけだが、
  **それは移行ではなく方針変更**であり、移行の commit に紛れ込ませるべきもの
  ではない。絞るなら別の決定として記録する。

  同型の移行（cloud-itonami/app-lo・app-ongakuka）で先にこの区別が行われて
  おり、こちらを合わせた。"
  [path]
  (when (str/starts-with? path "/xrpc/")
    (let [rest' (subs path (count "/xrpc/"))]
      (when (seq rest') rest'))))

(defn dispatch
  "method + path → 何をするか。Request も Response も知らない。

  返すのは `{:action …}` で、`:action` は
  `:page` / `:health` / `:xrpc` / `:cors-preflight` / `:not-found` /
  `:method-not-allowed` / `:bad-request` のいずれか。"
  [method path]
  (let [m (keyword (str/lower-case (or method "get")))
        p (or path "")]
    (cond
      (and (= m :options) (str/starts-with? p "/xrpc/"))
      {:action :cors-preflight}

      (str/starts-with? p "/xrpc/")
      (if (= m :post)
        (if-let [nsid (xrpc-nsid p)]
          {:action :xrpc :nsid nsid}
          {:action :bad-request :reason "Missing XRPC method"})
        {:action :method-not-allowed :allow "POST, OPTIONS"})

      (= p "/health") (if (= m :get)
                        {:action :health}
                        {:action :method-not-allowed :allow "GET"})
      (= p "/")       (if (= m :get)
                        {:action :page}
                        {:action :method-not-allowed :allow "GET"})
      :else {:action :not-found})))

(defn mcp-router-url
  "env の設定 → MCP router の URL。末尾スラッシュは落とす。

  既定値をここに焼くのは、設定が無いときに黙って何処かへ POST しないためでは
  なく、**どこへ行くのかを 1 箇所で読めるようにする**ため。呼び出し側はこの
  戻り値をそのまま使い、ページもこの値を表示する。"
  [{:keys [AGENTGATEWAY_MCP_ROUTER_URL MCP_ROUTER_URL]}]
  (let [pick (fn [s] (when (and (string? s) (seq (str/trim s))) (str/trim s)))]
    (-> (or (pick AGENTGATEWAY_MCP_ROUTER_URL)
            (pick MCP_ROUTER_URL)
            "https://mcp.etzhayyim.com/xrpc/com.etzhayyim.mcp.message")
        (str/replace #"/+$" ""))))

(defn declared-capabilities
  "`APP_CAPABILITIES`（wrangler が渡す JSON 配列の**文字列**）→ 名前の vector。

  **これを焼かないことが、この移行の要点である。** 移行前のページは
  `routeCount: 0` / `vars: []` を literal で持ち、隣の wrangler.jsonc が
  route 2 / var 8 を宣言していることに気づけなかった。同じ repo には operation
  数の宣言が 5 つあって 3 通りの数を言っている（docs/operator-quickstart.md）が、
  **実行時に consumer が読むのは `APP_CAPABILITIES` ただ 1 つ**なので、ページは
  それを読んで出す。設定が変われば表示も変わる。

  JSON parser は使わない —— `.cljc` を保つため、と、この値の形が
  『引用符で囲まれた名前の配列』だけだからである。読めない形なら空を返す
  （黙って壊れた表示をするより、何も主張しない方がよい）。"
  [s]
  (if (and (string? s) (seq (str/trim s)))
    (vec (map second (re-seq #"\"([^\"]*)\"" s)))
    []))

(defn unwrap-mcp
  "MCP router の応答から、呼び手に返す値を取り出す。

  `{:result {:structuredContent X}}` → X、`{:result X}` → X、それ以外は素通し。
  `{:error …}` は呼び出し側が 502 にするので、ここでは判定だけ返す。"
  [payload]
  (cond
    (and (map? payload) (contains? payload :error))
    {:ok? false :error (get-in payload [:error :message] "MCP router returned an error")
     :upstream payload}

    (and (map? payload) (contains? payload :result))
    (let [r (:result payload)]
      {:ok? true :value (if (and (map? r) (contains? r :structuredContent))
                          (:structuredContent r)
                          r)})

    :else {:ok? true :value payload}))
