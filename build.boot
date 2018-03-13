(set-env!
  :dependencies '[ [org.clojure/clojure "1.10.0-alpha4"]
                   [ring/ring-core "1.6.3"]
                   [org.immutant/web "2.1.10"]
                   [compojure "1.6.0"]
                   [rum "0.11.2"]
                   [org.clojure/clojurescript "1.10.145"]]

  :source-paths #{"src"}
  :resource-paths #{"resources" "build"})

(require
  '[blog.server :as server])

(def port "8080")

(deftask dev []
  (alter-var-root #'*warn-on-reflection* (constantly true))
  (server/start "-p" port))

(deftask test []
  (println "Nothing to test yet."))
