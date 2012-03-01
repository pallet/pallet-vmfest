(ns pallet.task.add-vmfest-image
  "A task for adding vmfest images"
  (:require
   [pallet.compute :as compute]
   [pallet.compute.vmfest :as vmfest]
   [pallet.core :as core]))

(defn as-str
  [sym]
  (if-let [n (namespace sym)]
    (str n "/" (name sym))
    (name sym)))

(defn add-vmfest-image
  "Add an image to the list of vmfest images."
  {:help-arglists '[[image-url]]}
  [request image-url]
  (let [service (:compute request)]
    (println "Downloading" (name image-url) "...")
    (vmfest/add-image service (as-str image-url))))
