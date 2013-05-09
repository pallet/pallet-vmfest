(ns pallet.compute.vmfest-test
  (:require
   [clojure.test :refer :all]
   [pallet.compute.vmfest :refer :all]
   [pallet.core :refer [server-spec]]
   [pallet.compute :refer [nodes instantiate-provider]]
   [pallet.crate.automated-admin-user :refer [automated-admin-user]]
   [pallet.live-test :refer [images test-for test-nodes]]
   [useful.ns :refer [alias-var]]))


;;; Initialise the vbox communication
(add-vbox-to-classpath (or (System/getProperty "vbox-comm") :xpcom))

(try
  (use '[pallet.api :only [plan-fn]])
  (catch Exception _
    (println _)
    (use '[pallet.phase :only [phase-fn]])
    (alias-var 'plan-fn (ns-resolve 'pallet.phase 'phase-fn))))

;; feature predicates
(defmacro ^{:private true} get-has-feature
  []
  (try
    (do
      (require 'pallet.feature)
      (when-not (ns-resolve 'pallet.compute.vmfest-test 'has-feature?)
        (use '[pallet.feature
               :only [has-feature? when-feature when-not-feature if-feature]])))
    (catch Exception e
      '(do
         (defmacro ^{:private true} has-feature? [_] false)
         (defmacro ^{:private true} if-feature [_ _ expr] expr)))))

(get-has-feature)


(deftest supported-providers-test
  (is (= ["vmfest" (supported-providers)])))

(if-feature taggable-nodes
  (do
    (use '[pallet.node :only [tag tags tag taggable?]])
    (require 'pallet.compute.vmfest.service)
    (defn test-tags [node]
      (when node
        (is (taggable? (:node node)))
        (is (= "vmfest-test-host"
               (tag (:node node)
                    pallet.compute.vmfest.service/group-name-tag))))))
  (defn test-tags [_]))

(deftest live-test
  (test-for [image (images)]
    (test-nodes
        [compute node-map node-types [:configure-dev :install :configure]]
        {:vmfest-test-host
         (server-spec
          :phases
          {:bootstrap (plan-fn (automated-admin-user))}
          :image image :count 1)}
      (let [service (instantiate-provider :vmfest)
            node (first (:vmfest-test-host node-map))]
        (clojure.tools.logging/infof "node-types %s" node-types)
        (clojure.tools.logging/infof "node-map %s" node-map)
        (is node)
        (is (seq (nodes service)))
        (test-tags node)))))
