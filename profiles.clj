{:dev {:dependencies
       [[org.clojure/clojure "1.4.0"]
        [com.palletops/pallet "0.8.0-beta.9"]
        [com.palletops/pallet "0.8.0-beta.9" :classifier "tests"]
        [ch.qos.logback/logback-classic "1.0.9"]]
       :plugins [[codox/codox.leiningen "0.6.4"]
                 [lein-marginalia "0.7.1"]]}
 :ws {:dependencies [[org.clojars.tbatchelli/vboxjws "4.2.4"]]}
 :xpcom {:dependencies [[org.clojars.tbatchelli/vboxjxpcom "4.2.4"]]}
 :no-checkouts {:checkout-shares ^:replace []} ; disable checkouts
 :doc {:dependencies [[com.palletops/pallet-codox "0.1.0"]]
       :codox {:writer codox-md.writer/write-docs
               :output-dir "doc/0.3/api"
               :src-dir-uri "https://github.com/pallet/pallet-repl/blob/develop"
               :src-linenum-anchor-prefix "L"}
       :aliases {"marg" ["marg" "-d" "doc/0.3/annotated"]
                 "codox" ["doc"]
                 "doc" ["do" "codox," "marg"]}} :release
 {:plugins [[lein-set-version "0.3.0"]]
  :set-version
  {:updates [{:path "README.md" :no-snapshot true}]}}}
