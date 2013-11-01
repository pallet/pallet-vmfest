(defproject com.palletops/pallet-vmfest "0.4.0-SNAPSHOT"
  :description "A pallet provider for using vmfest."
  :url "https://github.com/pallet/pallet-vmfest"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[vmfest "0.4.0-alpha.1"
                  :exclusions [org.clojure/clojure]]
                 [org.tcrawley/dynapath "0.2.3"
                  :exclusions [org.clojure/clojure]]])
