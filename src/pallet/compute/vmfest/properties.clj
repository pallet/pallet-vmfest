(ns pallet.compute.vmfest.properties
  "Namespace to guess the vbox.home system property"
  (:use
   [clojure.java.io :only [file]]))

;;; This has to be loaded before the vbox libs are initialised

(def vbox-install-locations
  ["/Applications/VirtualBox.app/Contents/MacOS/"
   "/usr/lib/virtualbox"])

(defn guess-vbox-home []
  (first (filter #(.isDirectory %) (map file vbox-install-locations))))

(when-let [vbox-home (or (System/getProperty "vbox.home") (guess-vbox-home))]
  (System/setProperty "vbox.home" (.getAbsolutePath vbox-home)))
