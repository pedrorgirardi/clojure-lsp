(ns lightcode.server.repl
  (:require
   [clojure.tools.nrepl :as nrepl]
   [clojure.spec.alpha :as s]
   [clojure.string :as str])
  (:refer-clojure :exclude [eval]))


(s/def ::op
  #{"info"
    "load-file"
    "ns-load-all"
    "ns-vars"})

(s/def ::session
  string?)


(s/def ::env
  #{"clj" "cljs"})


(s/def ::port
  int?)


(s/def ::host
  string?)


(s/def ::nrepl
  (s/keys :req-un [::port] :opt-un [::host]))


(s/def ::cljs-repl
  #{"shadow-cljs" "figwheel"})


(s/def ::start-cljs-repl-code
  string?)


(s/def ::context
  (s/keys :req-un [::env ::nrepl]
          :opt-un [::cljs-repl ::start-cljs-repl-code]))


(s/def ::message
  (s/keys :req-un [::op ::context]
          :opt-un [::session]))


;; -------------------------------------------------------------------


(defonce *sessions (atom {}))


(defn error-reply? [{:keys [ex err]}]
  (or ex err))


(defn cljs-repl-initialized? [{:keys [out] :as reply}]
  (and
   (not (error-reply? reply))
   (not (str/blank? out))
   (not (str/includes? out "Figwheel System not initialized"))))


(defn session! [{:keys [start-cljs-repl-code]} client env+port]
  (or (get @*sessions env+port)
      (let [[env port] env+port
            [new-session sessions] (case env
                                     ;; # CLJ session
                                     ;; Just create a new session and we're done
                                     "clj" (let [clj-session (nrepl/new-session client)]
                                             [clj-session {["clj" port] clj-session}])

                                     ;; # CLJS session
                                     ;; ClojureScript sessions are more tricky,
                                     ;; and we will do it in two steps:
                                     ;; 1. Create a new session by cloning the existing Clojure session.
                                     ;; 2. 'Upgrade' the REPL to a ClojureScript one.
                                     ;;
                                     ;; To 'upgrade' the REPL we need to know if
                                     ;; the project is using Figwheel or Shadow CLJS.
                                     ;; When using Figwheel we need to call the Figwheel Sidecar API,
                                     ;; for Shadow CLJS, well, the Shadow CLJS API.
                                     "cljs" (let [clj-session         (get @*sessions ["clj" port])
                                                  create-clj-session? (nil? clj-session)
                                                  clj-session         (or clj-session (nrepl/new-session client))
                                                  cljs-session        (nrepl/new-session client :clone clj-session)
                                                  cljs-repl-reply     (-> client
                                                                          (nrepl/message {:session cljs-session
                                                                                          :op      "eval"
                                                                                          :code    start-cljs-repl-code})
                                                                          (nrepl/combine-responses)
                                                                          doall)]

                                              (if (cljs-repl-initialized? cljs-repl-reply)
                                                [cljs-session (merge {["cljs" port] cljs-session}

                                                                     (when create-clj-session?
                                                                       {["clj" port] clj-session}))]

                                                ;; If the CLJS REPL wasn't initialized:
                                                ;; - return `nil` instead of a session.
                                                ;; - close the session, next time the client asks for a CLJS session we try again.
                                                ;; - return the sessions map with the Clojure session if we created one.
                                                (do
                                                  (doall (nrepl/message client {:op      "close"
                                                                                :session cljs-session}))

                                                  [nil (merge {}

                                                              (when create-clj-session?
                                                                {["clj" port] clj-session}))]))))]

        (swap! *sessions merge sessions)

        new-session)))



(defn with-session! [context f]
  (let [env      (get context :env)
        port     (get-in context [:nrepl :port])
        env+port [env port]]
    (when port
      (with-open [conn (nrepl/connect :port port)]
        (let [client  (nrepl/client conn 5000)
              session (session! context client env+port)]
          (f client session))))))


(defn eval [{:keys [context] :as message}]
  (with-session! context
    (fn [client session]
      (-> client
          (nrepl/message (assoc message :session session))
          (nrepl/combine-responses)
          doall))))

(s/fdef eval
        :args (s/cat :message ::message)
        :ret any?)


