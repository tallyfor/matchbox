(defproject thosmos/matchbox "3.5.4-SNAPSHOT"
  :description "Firebase bindings for Clojure(Script)"
  :url "http://github.com/thosmos/matchbox"
  :license {:name "Eclipse Public License" :url "http://www.eclipse.org/legal/epl-v10.html"}
  :authors ["verma", "crisptrutski", "thosmos"]

  :dependencies
  [[org.clojure/clojure "1.9.0" :scope "provided"]
   [org.clojure/clojurescript "1.10.238" :scope "provided"]
   [org.clojure/core.async "0.4.474" :scope "provided"]
   [reagent "0.8.0" :scope "provided"]
   [frankiesardo/linked "1.2.6" :scope "provided"]
   [com.google.firebase/firebase-admin "6.0.0"
    ;:exclusions
    ;#_[com.google.protobuf/protobuf-java com.google.errorprone/error_prone_annotations
    ; com.google.api/api-common com.google.auth/google-auth-library-credentials io.grpc/grpc-core
    ; com.google.auth/google-auth-library-oauth2-http]
    ]
   [org.apache.httpcomponents/httpclient "4.5.5"]
   [cljsjs/firebase "4.9.0-0"]
   [org.clojure/tools.namespace "0.2.11" :scope "test"]
   [doo "0.1.6"]]

  :aot [matchbox.clojure.android-stub]

  :profiles {:dev {:plugins [[lein-cljsbuild "1.1.3"]
                             [lein-doo "0.1.6"]
                             [com.jakemccrary/lein-test-refresh "0.6.0"]]}}

  :cljsbuild {:builds [{:id "test"
                        :source-paths ["src" "test"]
                        :compiler {:output-to "target/cljs/test.js"
                                   :main matchbox.runner
                                   :optimizations :none}}]})
