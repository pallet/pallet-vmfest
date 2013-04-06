(ns pallet.task.add-vmfest-image
  "A task for adding vmfest images"
  (:require
   [clojure.tools.logging :refer [debugf]]
   [pallet.compute :as compute]
   [pallet.compute.vmfest :as vmfest]
   [pallet.core :as core]
   [pallet.task :refer [abort]]
   [pallet.task-utils :refer [process-args]]
   [pallet.utils :refer [apply-map]]))

(def switches
  [["-o" "--os-family" "Specify the os family"]
   ["-v" "--os-version" "Specify the os version"]
   ["-l" "--os-64-bit" "Specify the os is 64 bit"]])

(def help
  (str "Install an image for vmfest.\n"
       \newline
       "For vagrant .box files, you will meed to specify the os-family,\n"
       "os-version, and os-64-bit, as these are not available in the\n"
       ".box file."
       \newline
       "add-vmfest-image image-url"
       \newline
       (last (process-args "nodes" nil switches))))

(defn process-options
  [options]
  (let [kw-opts (select-keys options [:os-family])]
    (merge
     options
     (zipmap (keys kw-opts) (map keyword (vals kw-opts))))))

(defn ^{:doc help} add-vmfest-image
  {:help-arglists []}
  [request & args]
  (let [[options [image-url]]
        (process-args "add-vmfest=image" args switches)
        service (:compute request)]
    (when-not image-url
      (abort "Must supply an image-url"))
    (println "Downloading" (name image-url) "...")
    (debugf "add-vmfest=image url %s options %s"
            image-url (process-options options))
    (let [options (process-options options)]
      (apply-map vmfest/add-image service image-url
                 ;; do not pass an empty :meta map when there are no
                 ;; options, as for vmfest, an empty map means you
                 ;; want the meta to be empty.
                 (if (seq options) {:meta options} {})))))
