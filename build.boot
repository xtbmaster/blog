(set-env!
  :dependencies '[ [org.clojure/clojure "1.10.0-alpha4"]
                   [ring/ring-core "1.6.3"]
                   [org.immutant/web "2.1.10"]
                   [compojure "1.6.0"]
                   [rum "0.11.2"]
                   [org.clojure/clojurescript "1.10.145"]
                   [adzerk/boot-cljs "2.1.4"]
                   [adzerk/boot-reload "0.5.2"]]

  :source-paths #{"src"}
  :resource-paths #{"resources"})

(require
  '[blog.server :as server]
  '[adzerk.boot-cljs :refer [cljs]]
  '[adzerk.boot-reload :refer [reload]])

(def port "8080")

(deftask blog-server []
  (fn middleware [next-handler]
    (fn handler [fileset]
      (server/start "-p" port)
      (next-handler fileset))))

(deftask dev []
  (comp
    (blog-server)
    (watch)
    (reload)
    (cljs)
    (target :dir #{"target"})))


        
(deftask test []
  (println "Nothing to test yet."))
