(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'et.pe/personalist)
(def version "0.0.1")
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"
                            :aliases [:xtdb]}))
(def uber-file (format "target/%s-%s-standalone.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src/clj" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis basis
                  :src-dirs ["src/clj"]
                  :class-dir class-dir
                  :ns-compile '[et.pe.server]
                  :java-opts ["--add-opens=java.base/java.nio=ALL-UNNAMED"
                              "-Dio.netty.tryReflectionSetAccessible=true"
                              "--enable-native-access=ALL-UNNAMED"]})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis basis
           :main 'et.pe.server})
  (println "Uberjar written to" uber-file))
