(set-env!
 :dependencies
 '[[org.clojure/clojure "1.9.0" :scope "provided"]
   [org.clojure/clojurescript "1.10.238" :scope "provided"]
   [org.clojure/core.async "0.4.474" :scope "provided"]
   ;; packaged dependencies
   [com.google.firebase/firebase-admin "6.0.0"
    :exclusions
    [
     ;com.google.protobuf/protobuf-java
     ;com.google.errorprone/error_prone_annotations
     ;com.google.api/api-common
     ;com.google.auth/google-auth-library-credentials
     ;io.grpc/grpc-core
     ;com.google.auth/google-auth-library-oauth2-http
     ]]
   [cljsjs/firebase "4.9.0-0"]
   [org.apache.httpcomponents/httpclient "4.5.5"]
   ;; optional namespace dependencies
   [reagent "0.8.0" :scope "provided"]
   [frankiesardo/linked "1.3.0" :scope "provided"]
   ;; build tooling
   [adzerk/boot-cljs "2.1.4" :scope "test"]
   [adzerk/bootlaces "0.1.13" :scope "test"]
   [adzerk/boot-test "1.2.0" :scope "test"]
   [crisptrutski/boot-cljs-test "0.3.4" :scope "test"]]
 :source-paths #{"src"})

(require
  '[adzerk.bootlaces :refer :all]
  '[adzerk.boot-cljs :refer :all]
  '[adzerk.boot-test :refer :all]
  '[crisptrutski.boot-cljs-test :refer [test-cljs]])

(def +version+ "0.1.0-SNAPSHOT")
(bootlaces! +version+)

(task-options!
  pom {:project 'thosmos/matchbox
       :version +version+
       :description "Firebase bindings for Clojure(Script)"
       :url "http://github.com/thosmos/matchbox"
       :scm {:url "http://github.com/thosmos/matchbox"}}
  aot {:namespace #{'matchbox.clojure.android-stub}}
  test-cljs {:js-env :phantom})

(deftask deps [] identity)

(deftask testing []
  (merge-env! :source-paths #{"test"})
  identity)

(deftask watch-js [] (comp (testing) (watch) (test-cljs)))

(deftask watch-jvm [] (comp (aot) (testing) (watch) (test)))

(deftask ci []
  (task-options!
    test {:junit-output-to "junit-out"}
    test-cljs {:exit? true})
  (comp (aot) (testing) (test) (test-cljs)))

(deftask build []
  (comp (pom) (aot) (jar)))
