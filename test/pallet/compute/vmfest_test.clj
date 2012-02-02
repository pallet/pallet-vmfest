(ns pallet.compute.vmfest-test
  (:use
   clojure.test
   pallet.compute.vmfest))

(deftest supported-providers-test
  (is (= ["vmfest" (supported-providers)])))
