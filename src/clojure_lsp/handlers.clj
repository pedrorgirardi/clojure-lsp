(ns clojure-lsp.handlers
  (:require
   [clojure-lsp.clojure-core :as cc]
   [clojure-lsp.db :as db]
   [clojure-lsp.parser :as parser]
   [clojure-lsp.refactor.transform :as refactor]
   [clojure.core.async :as async]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.set :as set]
   [clojure.string :as string]
   [clojure.tools.logging :as log]
   [digest :as digest]
   [rewrite-clj.node :as n]
   [rewrite-clj.zip :as z]
   [cljfmt.core :as cljfmt])
  (:import
   [java.util.jar JarFile]))

(defonce diagnostics-chan (async/chan 1))
(defonce edits-chan (async/chan 1))

(defn- uri->path [uri]
  (string/replace uri #"^file:///" "/"))

(defn- ->range [{:keys [row end-row col end-col]}]
  {:start {:line (dec row) :character (dec col)}
   :end {:line (dec end-row) :character (dec end-col)}})

(defn check-bounds [line column {:keys [row end-row col end-col]}]
  (cond
    (< line row) :before
    (and (= line row) (< column col)) :before
    (< line end-row) :within
    (and (= end-row line) (>= end-col column)) :within
    :else :after))

(defn find-reference-under-cursor [line column env]
  (first (filter (comp #{:within} (partial check-bounds line column)) (:usages env))))

(defn send-notifications [uri env]
  (let [unknown-usages (seq (filter (fn [reference] (contains? (:tags reference) :unknown))
                                    (:usages env)))
        aliases (set/map-invert (:project-aliases @db/db))]
    (async/put! diagnostics-chan {:uri uri
                                  :diagnostics
                                  (for [usage unknown-usages
                                        :let [known-alias? (some-> (:unkown-ns usage)
                                                                   aliases)
                                              problem (if known-alias?
                                                        :require
                                                        :unknown)]]
                                    {:range (->range usage)
                                     :code problem
                                     :message (case problem
                                                :unknown "Unknown symbol"
                                                :require "Needs Require")
                                     :severity 1})})))

(defn safe-find-references
  ([uri text]
   (safe-find-references uri text true))
  ([uri text diagnose?]
   (try
     #_(log/warn "trying" uri (get-in @db/db [:documents uri :v]))
     (let [references (parser/find-references text)]
       (when diagnose?
         (send-notifications uri references))
       references)
     (catch Throwable e
       (log/warn "Ignoring: " uri (.getMessage e))
       ;; On purpose
       nil))))

(defn did-open [uri text]
  (when-let [references (safe-find-references uri text)]
    (swap! db/db (fn [state-db]
                   (-> state-db
                       (assoc-in [:documents uri] {:v 0 :text text})
                       (assoc-in [:file-envs uri] references)))))
  text)

(defn crawl-jars [jars]
  (let [xf (comp
            (mapcat (fn [jar-file]
                      (let [jar (JarFile. jar-file)]
                        (->> jar
                             (.entries)
                             (enumeration-seq)
                             (remove #(.isDirectory %))
                             (map (fn [entry]
                                    [(str "zipfile://" jar-file "::" (.getName entry))
                                     entry
                                     jar]))))))
            (filter (fn [[uri _ _]]
                      (or (string/ends-with? uri ".clj")
                          (string/ends-with? uri ".cljc")
                          (string/ends-with? uri ".cljs"))))
            (map (fn [[uri entry jar]]
                   (let [text (with-open [stream (.getInputStream jar entry)]
                                (slurp stream))]
                     [uri (safe-find-references uri text false)])))
            (remove (comp nil? second)))
        output-chan (async/chan)]
    (async/pipeline-blocking 5 output-chan xf (async/to-chan jars) true (fn [e] (log/warn e "hello")))
    (async/<!! (async/into {} output-chan))))

(defn crawl-source-dirs [dirs]
  (let [xf (comp
            (mapcat file-seq)
            (filter #(.isFile %))
            (map #(str "file://" (.getAbsolutePath %)))
            (filter (fn [uri]
                      (or (string/ends-with? uri ".clj")
                          (string/ends-with? uri ".cljc")
                          (string/ends-with? uri ".cljs"))))
            (map (juxt identity (fn [uri] (safe-find-references uri (slurp uri) false))))
            (remove (comp nil? second)))
        output-chan (async/chan)]
    (async/pipeline-blocking 5 output-chan xf (async/to-chan dirs) true (fn [e] (log/warn e "hello")))
    (async/<!! (async/into {} output-chan))))

(defn lookup-classpath [project-root]
  (try
    (let [root-path (uri->path project-root)
          sep (re-pattern (System/getProperty "path.separator"))]
      (-> (shell/sh "lein" "classpath" :dir root-path)
          (:out)
          (string/trim-newline)
          (string/split sep)))
    (catch Exception e
      (log/warn "Could not run lein in" project-root (.getMessage e)))))

(defn determine-dependencies [project-root client-settings]
  (let [root-path (uri->path project-root)
        source-paths (mapv #(io/file (str root-path "/" %))
                           (get client-settings "source-paths" ["src"]))
        project-file (io/file root-path "project.clj")]
    (if (.exists project-file)
      (let [project-hash (digest/md5 project-file)
            loaded (db/read-deps root-path)
            use-cp-cache (= (:project-hash loaded) project-hash)
            classpath (if use-cp-cache
                        (:classpath loaded)
                        (lookup-classpath project-root))
            jars (filter #(.isFile %) (map io/file (reverse classpath)))
            jar-envs (if use-cp-cache
                       (:jar-envs loaded)
                       (crawl-jars jars))
            file-envs (crawl-source-dirs source-paths)]
        (db/save-deps root-path project-hash classpath jar-envs)
        (merge file-envs jar-envs))
      (crawl-source-dirs source-paths))))

(defn initialize [project-root client-capabilities client-settings]
  (when project-root
    (let [file-envs (determine-dependencies project-root client-settings)]
      (swap! db/db assoc
             :client-capabilities client-capabilities
             :client-settings client-settings
             :project-root project-root
             :file-envs file-envs
             :project-aliases (apply merge (map (comp :aliases val) file-envs))))))

(defn namespaces-and-aliases [local-aliases project-aliases remote-envs]
  (map (fn [[doc-id remote-env]]
         (let [ns-sym (:ns remote-env)
               project-alias (get project-aliases ns-sym)
               as-alias (cond-> ""
                          project-alias (str " :as " (name project-alias)))
               local-alias (get local-aliases ns-sym)]
           {:ns ns-sym
            :local-alias local-alias
            :project-alias project-alias
            :as-alias as-alias
            :ref (or local-alias project-alias ns-sym)
            :usages (:usages remote-env)}))
       remote-envs))

(defn completion [doc-id line column]
  (let [file-envs (:file-envs @db/db)
        local-env (get file-envs doc-id)
        remote-envs (dissoc file-envs doc-id)
        {:keys [add-require? row col]} (:require-pos local-env)
        cursor-usage (find-reference-under-cursor line column local-env)
        cursor-value (some-> cursor-usage :str)
        matches-cursor? #(when (and % (string/starts-with? % cursor-value))
                           %)
        remotes (namespaces-and-aliases (:aliases local-env) (:project-aliases @db/db) remote-envs)]
    (concat
     (->> (:usages local-env)
          (filter (comp :declare :tags))
          (filter (comp matches-cursor? :str))
          (remove (fn [usage]
                    (when-let [scope-bounds (:scope-bounds usage)]
                      (not= :within (check-bounds line column scope-bounds)))))
          (map (fn [{:keys [sym]}] {:label (name sym)}))
          (sort-by :label))
     (->>
       (for [remote remotes
             usage (:usages remote)
             :when (contains? (:tags usage) :public)
             :let [match-ns-str (or (matches-cursor? (str (:local-alias remote)))
                                    (matches-cursor? (str (:project-alias remote)))
                                    (matches-cursor? (str (:ns remote))))
                   match-sym-str (matches-cursor? (some-> (:sym usage) name))]
             :when (or match-ns-str match-sym-str)
             :let [label (if match-ns-str
                           (format "%s/%s" match-ns-str (name (:sym usage)))
                           match-sym-str)
                   insert-text (format "%s/%s" (name (:ref remote)) (name (:sym usage)))]]
        (cond-> {:label label :detail (str (:ns remote))}
          match-sym-str (assoc :text-edit {:range (->range cursor-usage)
                                           :new-text insert-text})
          (not (contains? (:requires local-env) (:ns remote)))
          (assoc :additional-text-edits [{:range (->range {:row row :col col :end-row row :end-col col})
                                          :new-text (if add-require?
                                                      (format "\n  (:require\n   [%s%s])" (name (:ns remote)) (:as-alias remote))
                                                      (format "\n   [%s%s]" (name (:ns remote)) (:as-alias remote)))}])))
      (sort-by :label))
     (->> cc/core-syms
          (filter (comp matches-cursor? str))
          (map (fn [sym] {:label (str sym)}))
          (sort-by :label))
     (->> cc/java-lang-syms
          (filter (comp matches-cursor? str))
          (map (fn [sym] {:label (str sym)}))
          (sort-by :label)))))

(defn references [doc-id line column]
  (let [file-envs (:file-envs @db/db)
        local-env (get file-envs doc-id)
        cursor-sym (:sym (find-reference-under-cursor line column local-env))]
    (log/warn "references" doc-id line column)
    (into []
          (for [[uri {:keys [usages]}] (:file-envs @db/db)
                {:keys [sym] :as usage} usages
                :when (= sym cursor-sym)]
            {:uri uri
             :range (->range usage)}))))

(defn did-change [uri text version]
  ;; Ensure we are only accepting newer changes
  (loop [state-db @db/db]
    (when (> version (get-in state-db [:documents uri :v] -1))
      (when-let [references (safe-find-references uri text)]
        (when-not (compare-and-set! db/db state-db (-> state-db
                                                       (assoc-in [:documents uri] {:v version :text text})
                                                       (assoc-in [:file-envs uri] references)))
          (recur @db/db))))))

(defn rename [doc-id line column new-name]
  (let [file-envs (:file-envs @db/db)
        local-env (get file-envs doc-id)
        {cursor-sym :sym cursor-str :str tags :tags} (find-reference-under-cursor line column local-env)]
    (when-not (contains? tags :norename)
      (let [[_ cursor-ns _] (parser/ident-split cursor-str)
            replacement (if cursor-ns
                          (string/replace new-name (re-pattern (str "^" cursor-ns "/")) "")
                          new-name)
            changes (->> (for [[doc-id {:keys [usages]}] file-envs
                               :let [version (get-in @db/db [:documents doc-id :v] 0)]
                               {u-sym :sym u-str :str :as usage} usages
                               :when (= u-sym cursor-sym)
                               :let [[u-prefix u-ns _] (parser/ident-split u-str)]]
                           {:range (->range usage)
                            :new-text (str u-prefix u-ns (when u-ns "/") replacement)
                            :text-document {:version version :uri doc-id}})
                         (group-by :text-document)
                         (remove (comp empty? val))
                         (map (fn [[text-document edits]]
                                {:text-document text-document
                                 :edits edits})))]
        (if (get-in @db/db [:client-capabilities :workspace :workspace-edit :document-changes])
          {:document-changes changes}
          {:changes (into {} (map (fn [{:keys [text-document edits]}]
                                    [(:uri text-document) edits])
                                  changes))})))))

(defn definition [doc-id line column]
  (let [file-envs (:file-envs @db/db)
        local-env (get file-envs doc-id)
        cursor-sym (:sym (find-reference-under-cursor line column local-env))]
    (log/warn "definition" doc-id line column cursor-sym)
    (first
     (for [[env-doc-id {:keys [usages]}] file-envs
           {:keys [sym tags] :as usage} usages
           :when (= sym cursor-sym)
           :when (and (or (= doc-id env-doc-id) (:public tags))
                      (:declare tags))]
       {:uri env-doc-id :range (->range usage)}))))

(def refactorings
  {"cycle-coll" #'refactor/cycle-coll
   "thread-first" #'refactor/thread-first
   "thread-first-all" #'refactor/thread-first-all
   "thread-last" #'refactor/thread-last
   "thread-last-all" #'refactor/thread-last-all
   "move-to-let" #'refactor/move-to-let
   "introduce-let" #'refactor/introduce-let
   "expand-let" #'refactor/expand-let
   "add-missing-libspec" #'refactor/add-missing-libspec})

(defn refactor [doc-id line column refactoring args]
  (try
    (let [;; TODO Instead of v=0 should I send a change AND a document change
          {:keys [v text] :or {v 0}} (get-in @db/db [:documents doc-id])
          result (apply (get refactorings refactoring) (parser/loc-at-pos text line column) args)
          changes [{:text-document {:uri doc-id :version v}
                    :edits (mapv #(update % :range ->range) (refactor/result result))}]]
      (if (get-in @db/db [:client-capabilities :workspace :workspace-edit :document-changes])
        {:document-changes changes}
        {:changes (into {} (map (fn [{:keys [text-document edits]}]
                                  [(:uri text-document) edits])
                                changes))}))
    (catch Exception e
      (log/error e "could not refactor" (.getMessage e)))))

(defn hover [doc-id line column]
  (let [file-envs (:file-envs @db/db)
        local-env (get file-envs doc-id)
        cursor (find-reference-under-cursor line column local-env)
        signatures (first
                    (for [[_ {:keys [usages]}] file-envs
                          {:keys [sym tags] :as usage} usages
                          :when (and (= sym (:sym cursor)) (:declare tags))]
                      (:signatures usage)))
        [content-format] (get-in @db/db [:client-capabilities :text-document :hover :content-format])]
    (if cursor
      {:range (->range cursor)
       :contents (case content-format
                   "markdown" (let [{:keys [sym]} cursor
                                    signatures (string/join "\n" signatures)]
                                {:kind "markdown"
                                 :value (str "```clojure\n" sym "\n" signatures "```")})

                     ;; default to plaintext
                   [(cond-> (select-keys cursor [:sym :tags])
                      (seq signatures) (assoc :signatures signatures)
                      :always (pr-str))])}
      {:contents []})))

(defn formatting [doc-id]
  (let [{:keys [text]} (get-in @db/db [:documents doc-id])
        new-text (cljfmt/reformat-string text {:remove-consecutive-blank-lines? false})]
    (when-not (= new-text text)
      [{:range (->range {:row 1 :col 1 :end-row 1000000 :end-col 1000000})
        :new-text new-text}])))

(defn range-formatting [doc-id format-pos]
  (log/warn "range-formatting" format-pos)
  (let [{:keys [text]} (get-in @db/db [:documents doc-id])
        forms (parser/find-top-forms-in-range text format-pos)]
    (mapv (fn [form-loc]
            {:range (->range (-> form-loc z/node meta))
             :new-text (n/string (cljfmt/reformat-form (z/node form-loc) {:remove-consecutive-blank-lines? false}))})
          forms)))
