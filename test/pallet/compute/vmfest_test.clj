(ns pallet.compute.vmfest-test
  (:require
   [clojure.test :refer :all]
   [pallet.actions :refer [exec-script*]]
   [pallet.compute.vmfest :refer :all]
   [pallet.compute :refer [nodes instantiate-provider]]
   [pallet.crate.automated-admin-user :as automated-admin-user
    :refer [create-admin-user]]
   [pallet.live-test :refer [images test-for test-nodes]]
   [pallet.group :refer [converge group-spec lift]]
   [pallet.plan :refer [plan-fn]]
   [pallet.spec :refer [server-spec]]
   [useful.ns :refer [alias-var]]))

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
          {:phases
           {:bootstrap (plan-fn [session] (create-admin-user session))}
           :image image :count 1})}
      (let [service (instantiate-provider :vmfest {})
            node (first (:vmfest-test-host node-map))]
        (clojure.tools.logging/infof "node-types %s" node-types)
        (clojure.tools.logging/infof "node-map %s" node-map)
        (is node)
        (is (seq (nodes service)))
        (test-tags node)))))

(deftest converge-test
  (testing "basic converge"
    (let [spec (server-spec {})
          node-spec {:image {:image-id :ubuntu-13.04-64bit :os-family :ubuntu}}
          group (group-spec :agroup {:extends [spec] :node-spec node-spec})
          service (instantiate-provider :vmfest {})]
      (converge (assoc group :count 1) :compute service :os-detect false)
      (converge (assoc group :count 0) :compute service :os-detect false)))
  (testing "converge with a-a-u"
    (let [spec (server-spec
                {:extends [(automated-admin-user/server-spec {})]
                 :phases
                 {:configure (plan-fn [session]
                               (exec-script* session "ls"))}})
          node-spec {:image {:image-id :ubuntu-13.04-64bit :os-family :ubuntu}}
          group (group-spec :agroup {:extends [spec] :node-spec node-spec})
          service (instantiate-provider :vmfest {})
          result (converge (assoc group :count 1) :compute service)]
      (is (= 1 (count (:new-targets result))))
      (is (= 3 (count (:results result))))
      (is (= #{:settings :bootstrap :configure}
             (set (map :phase (:results result)))))
      (let [result (converge (assoc group :count 0) :compute service)]
        (is (zero? (count (:new-targets result))))
        (is (= 1 (count (:old-targets result))))))))
