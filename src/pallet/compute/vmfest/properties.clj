(ns pallet.compute.vmfest.properties
  "Namespace to guess the vbox.home system property"
  (:use
   [clojure.java.io :only [file]]
   [clojure.tools.logging :only [debugf warnf]]))

;;; This has to be loaded before the vbox libs are initialised

(def vbox-install-locations
  ["/Applications/VirtualBox.app/Contents/MacOS/"
   "/usr/lib/virtualbox"])

(defn guess-vbox-home []
  (first (filter #(.isDirectory %) (map file vbox-install-locations))))

(if-let [vbox-home (System/getProperty "vbox.home")]
  (if (.isDirectory (file vbox-home))
    (debugf "The vbox.home system property supplied as %s" vbox-home)
    (warnf
     "The vbox.home system property is set to %s, but that path does not exist."
     vbox-home))
  (when-let [vbox-home (guess-vbox-home)]
    (debugf
     "The vbox.home system property guessed as %s"
     (.getAbsolutePath vbox-home))
    (System/setProperty "vbox.home" (.getAbsolutePath vbox-home))))
