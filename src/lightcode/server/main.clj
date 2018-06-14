(ns lightcode.server.main
  (:require
   [clojure-lsp.db :as db]
   [clojure-lsp.handlers :as handlers]
   [clojure-lsp.interop :as interop]
   [clojure.tools.logging :as log]
   [clojure.tools.nrepl.server :as nrepl.server]
   [clojure.string :as string]
   [clojure.core.async :as async])
  (:import
   (org.eclipse.lsp4j.services LanguageServer TextDocumentService WorkspaceService LanguageClient)
   (org.eclipse.lsp4j
    ApplyWorkspaceEditParams
    CodeActionParams
    Command
    CompletionItemKind
    CompletionOptions
    CompletionParams
    ConfigurationItem
    ConfigurationParams
    DidChangeConfigurationParams
    DidChangeTextDocumentParams
    DidChangeWatchedFilesParams
    DidCloseTextDocumentParams
    DidOpenTextDocumentParams
    DidSaveTextDocumentParams
    DocumentFormattingParams
    DocumentRangeFormattingParams
    ExecuteCommandParams
    InitializeParams
    InitializeResult
    InitializedParams
    ParameterInformation
    ReferenceParams
    RenameParams
    SaveOptions
    ServerCapabilities
    SignatureHelp
    SignatureHelpOptions
    SignatureInformation
    TextDocumentContentChangeEvent
    TextDocumentPositionParams
    TextDocumentSyncKind
    TextDocumentSyncOptions)
   (org.eclipse.lsp4j.launch LSPLauncher)
   (java.util.concurrent CompletableFuture)
   (java.util.function Supplier))
  (:gen-class))

(defonce formatting (atom false))

(deftype LSPTextDocumentService []
  TextDocumentService
  (^void didOpen [this ^DidOpenTextDocumentParams params]
    (log/warn "DidOpenTextDocumentParams")
    (let [document (.getTextDocument params)]
      (#'handlers/did-open (.getUri document) (.getText document))))

  (^void didChange [this ^DidChangeTextDocumentParams params]
    (let [textDocument (.getTextDocument params)
          version (.getVersion textDocument)
          changes (.getContentChanges params)
          text (.getText ^TextDocumentContentChangeEvent (.get changes 0))
          uri (.getUri textDocument)]
      (#'handlers/did-change uri text version)))

  (^void didSave [this ^DidSaveTextDocumentParams params]
    (log/warn "DidSaveTextDocumentParams"))
  (^void didClose [this ^DidCloseTextDocumentParams params]
    (log/warn "DidCloseTextDocumentParams")
    (swap! db/db update :documents dissoc (.getUri (.getTextDocument params))))

  (^CompletableFuture references [this ^ReferenceParams params]
    (log/warn params)
    (CompletableFuture/supplyAsync
     (reify Supplier
       (get [this]
         (try
           (let [doc-id (.getUri (.getTextDocument params))
                 pos (.getPosition params)
                 line (inc (.getLine pos))
                 column (inc (.getCharacter pos))]
             (interop/conform-or-log ::interop/references (#'handlers/references doc-id line column)))
           (catch Exception e
             (log/error e)))))))

  (^CompletableFuture completion [this ^CompletionParams params]
    (CompletableFuture/supplyAsync
     (reify Supplier
       (get [this]
         (try
           (let [doc-id (.getUri (.getTextDocument params))
                 pos (.getPosition params)
                 line (inc (.getLine pos))
                 column (inc (.getCharacter pos))]
             (interop/conform-or-log ::interop/completion-items (#'handlers/completion doc-id line column)))
           (catch Exception e
             (log/error e)))))))

  (^CompletableFuture rename [this ^RenameParams params]
    (log/warn params)
    (CompletableFuture/supplyAsync
     (reify Supplier
       (get [this]
         (try
           (let [doc-id (.getUri (.getTextDocument params))
                 pos (.getPosition params)
                 line (inc (.getLine pos))
                 column (inc (.getCharacter pos))
                 new-name (.getNewName params)]
             (interop/conform-or-log ::interop/workspace-edit (#'handlers/rename doc-id line column new-name)))
           (catch Exception e
             (log/error e)))))))

  (^CompletableFuture hover [this ^TextDocumentPositionParams params]
    (CompletableFuture/supplyAsync
     (reify Supplier
       (get [this]
         (try
           (let [doc-id (.getUri (.getTextDocument params))
                 pos (.getPosition params)
                 line (inc (.getLine pos))
                 column (inc (.getCharacter pos))]
             (interop/conform-or-log ::interop/hover (#'handlers/hover doc-id line column)))
           (catch Exception e
             (log/error e)))))))

  (^CompletableFuture signatureHelp [this ^TextDocumentPositionParams params]
    (CompletableFuture/completedFuture
     (SignatureHelp. [(doto (SignatureInformation. "sign-label")
                        (.setDocumentation "docs")
                        (.setParameters [(ParameterInformation. "param label" "param doc")]))]
                     0 0)))

  (^CompletableFuture formatting [this ^DocumentFormattingParams params]
    (CompletableFuture/supplyAsync
     (reify Supplier
       (get [this]
         (try
           (let [doc-id (.getUri (.getTextDocument params))]
             (interop/conform-or-log ::interop/edits (#'handlers/formatting doc-id)))
           (catch Exception e
             (log/error e)))))))

  (^CompletableFuture rangeFormatting [this ^DocumentRangeFormattingParams params]
    (let [result (when (compare-and-set! formatting false true)
                   (try
                     (let [doc-id (.getUri (.getTextDocument params))
                           range (.getRange params)
                           start (.getStart range)
                           end (.getEnd range)]
                       (interop/conform-or-log ::interop/edits (#'handlers/range-formatting
                                                                doc-id
                                                                {:row (inc (.getLine start))
                                                                 :col (inc (.getCharacter start))
                                                                 :end-row (inc (.getLine end))
                                                                 :end-col (inc (.getCharacter end))})))
                     (catch Exception e
                       (log/error e))
                     (finally
                       (reset! formatting false))))]
      (CompletableFuture/completedFuture
       result)))

  (^CompletableFuture codeAction [this ^CodeActionParams params]
    (log/warn params)
    (CompletableFuture/completedFuture
     (let [start (.getStart (.getRange params))]
       [(Command. "add-missing-libspec" "add-missing-libspec"
                  [(.getUri (.getTextDocument params)) (.getLine start) (.getCharacter start)])])))

  (^CompletableFuture definition [this ^TextDocumentPositionParams params]
    (CompletableFuture/supplyAsync
     (reify Supplier
       (get [this]
         (try
           (let [doc-id (.getUri (.getTextDocument params))
                 pos (.getPosition params)
                 line (inc (.getLine pos))
                 column (inc (.getCharacter pos))]
             (interop/conform-or-log ::interop/location (#'handlers/definition doc-id line column)))
           (catch Exception e
             (log/error e))))))))

(defn- path->uri [path]
  (if (string/starts-with? path "/")
    (str "file://" path)
    path))

(deftype LSPWorkspaceService []
  WorkspaceService
  (^CompletableFuture executeCommand [this ^ExecuteCommandParams params]
    (log/warn params)
    (let [[doc-id line col & args] (map interop/json->clj (.getArguments params))]
      (future
        (try
          (let [result (#'handlers/refactor (path->uri doc-id)
                                            (inc (int line))
                                            (inc (int col))
                                            (.getCommand params)
                                            args)]
            (.get (.applyEdit (:client @db/db)
                              (ApplyWorkspaceEditParams.
                               (interop/conform-or-log ::interop/workspace-edit result)))))
          (catch Exception e
            (log/error e)))))
    (CompletableFuture/completedFuture 0))
  (^void didChangeConfiguration [this ^DidChangeConfigurationParams params]
    (log/warn params))
  (^void didChangeWatchedFiles [this ^DidChangeWatchedFilesParams params]
    (log/warn "DidChangeWatchedFilesParams")))

(defrecord LSPServer []
  LanguageServer
  (^CompletableFuture initialize [this ^InitializeParams params]
    (let [client-capabilities (some->> params (.getCapabilities) (interop/conform-or-log ::interop/client-capabilities))]
      (log/warn "Initialize" client-capabilities)
      (#'handlers/initialize (.getRootUri params) client-capabilities (interop/json->clj (.getInitializationOptions params))))
    (CompletableFuture/completedFuture
     (InitializeResult. (doto (ServerCapabilities.)
                          (.setHoverProvider true)

                          ;; Don't want this yet
                          ; (.setCodeActionProvider true)

                          (.setReferencesProvider true)
                          (.setRenameProvider true)
                          (.setDefinitionProvider true)
                          (.setDocumentFormattingProvider true)
                          (.setDocumentRangeFormattingProvider true)
                          (.setTextDocumentSync (doto (TextDocumentSyncOptions.)
                                                  (.setOpenClose true)
                                                  (.setChange TextDocumentSyncKind/Full)
                                                  (.setSave (SaveOptions. true))))
                          (.setCompletionProvider (CompletionOptions. false [\c]))))))
  (^void initialized [this ^InitializedParams params]
    (log/warn "Initialized" params))
  (^CompletableFuture shutdown [this]
    (log/info "Shutting down")
    (reset! db/db {:documents {}}) ;; TODO confirm this is correct
    (CompletableFuture/completedFuture
     {:result nil}))
  (exit [this]
    (log/info "Exit")
    (shutdown-agents)
    (System/exit 0))
  (getTextDocumentService [this]
    (LSPTextDocumentService.))
  (getWorkspaceService [this]
    (LSPWorkspaceService.)))


(defn -main [& args]
  (log/info "Light Code server started")
  (let [server      (LSPServer.)
        launcher    (LSPLauncher/createServerLauncher server System/in System/out)]

    ; repl-server (nrepl.server/start-server)
    ; (log/info "nREPL server started on port" (:port repl-server))

    (swap! db/db assoc :client ^LanguageClient (.getRemoteProxy launcher))

    (async/go
      (loop [edit (async/<! handlers/edits-chan)]
        (log/warn "edit applied?" (.get (.applyEdit (:client @db/db) (ApplyWorkspaceEditParams. (interop/conform-or-log ::interop/workspace-edit edit)))))
        (recur (async/<! handlers/edits-chan))))

    (async/go
      (loop [diagnostic (async/<! handlers/diagnostics-chan)]
        (.publishDiagnostics (:client @db/db) (interop/conform-or-log ::interop/publish-diagnostics-params diagnostic))
        (recur (async/<! handlers/diagnostics-chan))))

    (.startListening launcher)))
