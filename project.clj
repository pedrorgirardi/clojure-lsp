(defproject clojure-lsp "0.1.0-SNAPSHOT"

  :java-source-paths ["src"]

  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/tools.reader "1.2.1"]
                 [org.eclipse.lsp4j/org.eclipse.lsp4j "0.4.0" :exclusions [org.eclipse.xtend/org.eclipse.xtend.lib]]
                 [org.eclipse.xtend/org.eclipse.xtend.lib "2.13.0" :exclusions [com.google.guava/guava]]
                 [com.google.guava/guava "19.0"]
                 [rewrite-clj "0.6.0"]
                 [log4j/log4j "1.2.17"]
                 [org.clojure/tools.logging "0.3.1"]
                 [org.clojure/tools.nrepl "0.2.12"]
                 [org.clojure/core.async "0.4.474"]
                 [org.xerial/sqlite-jdbc "3.21.0.1"]
                 [funcool/clojure.jdbc "0.9.0"]
                 [digest "1.4.8"]
                 [cljfmt "0.5.7"]
                 [medley "1.0.0"]]
  :jvm-opts ^:replace ["-Xmx1g" "-server"]
  :main lightcode.server.main
  :profiles {:dev     {:plugins [[com.jakemccrary/lein-test-refresh "0.22.0"]
                                 [lein-bin "0.3.4"]
                                 [lein-repl-run "0.1.0"]]
                       :bin     {:name "lightcode"}}
             :test    {:test-selectors {:focused :focused}}
             :uberjar {:aot :all}})
