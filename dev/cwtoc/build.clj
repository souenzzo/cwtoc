(ns cwtoc.build
  (:require [clojure.tools.build.api :as b]))

(def lib 'cwtoc/server)
(def class-dir "target/classes")
(def uber-file "target/cwtoc.jar")

(defn -main
  [& _]
  (let [basis (b/create-basis {:project "deps.edn"})]
    (b/delete {:path "target"})
    (b/write-pom {:class-dir class-dir
                  :lib       lib
                  :version   "1.0.0"
                  :basis     basis})
    #_(b/copy-dir {:src-dirs   (:paths basis)
                   :target-dir class-dir})
    (b/compile-clj {:basis     basis
                    :class-dir class-dir})
    (b/uber {:class-dir class-dir
             :main      'cwtoc.server
             :uber-file uber-file
             :basis     basis})
    (shutdown-agents)))
;; docker build --build-arg VERSION=$(git rev-parse HEAD) -f Dockerfile -t registry.heroku.com/cwtoc/web .
;; docker push registry.heroku.com/cwtoc/web
;; heroku container:release web -a cwtoc
;; heroku logs --tail -a cwtoc
