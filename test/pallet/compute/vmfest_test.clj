(ns pallet.compute.vmfest-test
  (:use
   clojure.test
   pallet.compute.vmfest
   [pallet.core :only [server-spec]]
   [pallet.compute :only [nodes compute-service]]
   [pallet.crate.automated-admin-user :only [automated-admin-user]]
   [pallet.live-test :only [images test-for test-nodes]]
   [useful.ns :only [alias-var]]))

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
    (defn test-tags [node]
      (when node
        (is (taggable? (:node node)))
        (is (= "vmfest-test-host" (tag (:node node) group-name-tag))))))
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
      (let [service (compute-service :vmfest)
            node (first (:vmfest-test-host node-map))]
        (clojure.tools.logging/infof "node-types %s" node-types)
        (clojure.tools.logging/infof "node-map %s" node-map)
        (is node)
        (is (seq (nodes service)))
        (test-tags node)))))
