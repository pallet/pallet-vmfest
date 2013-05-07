(ns pallet.compute.vmfest
  "The VMFest provider allows Pallet to use VirtualBox via VMFest.

   Example Configuration
   ---------------------

   An example service configuration in `~/.pallet/config.clj`

       :vb {:provider \"vmfest\"
            :default-local-interface \"vboxnet0\"
            :default-bridged-interface \"en1: Wi-Fi 2 (AirPort)\"
            :default-network-type :local
            :vbox-comm :xpcom ;; or :ws for web services
            :hardware-models
            {:test
             {:memory-size 768
              :cpu-count 1}
             :test-2
             {:memory-size 512
              :network-type :bridged}
            :images {:centos-5-3 {:description \"CentOS 5.3 32bit\"
                                  :uuid \"4697bdf7-7acf-4a20-8c28-e20b6bb58e25\"
                                  :os-family :centos
                                  :os-version \"5.3\"
                                  :os-type-id \"RedHat\"}
                     :ubuntu-10-04 {:description \"Ubuntu 10.04 32bit\"
                                    :uuid \"8a31e3aa-0d46-41a5-936d-25130dcb16b7\"
                                    :os-family :ubuntu
                                    :os-version \"10.04\"
                                    :os-type-id \"Ubuntu\"
                                    :username
                                    :password}}
            :model-path \"/Volumes/My Book/vms/disks\"
            :node-path \"/Volumes/My Book/vms/nodes\"}

   The uuid's can be found using `vboxmanage`

       vboxmanage list hdds

    or it can be the path to time image file itself (.vdi).

   The images are disks that are immutable.  The virtualbox extensions need
   to be installed on the image.

   Communication with the VirtualBox subsystem
   -------------------------------------------
   There are two ways to connect to VirtualBox: XPCOM and Web Services.

   The Web Services method is the most universal way to access the
   VBox subsystem, as it works either for local or remote hosts, and
   also is generally better supported, but it has the drawback that it
   is slower and it requires the `vboxwebsrv` process to be running.

   XPCOM is faster and easier to set up (no configuration), but only
   works when the VMs are in the same host as Pallet.

   `pallet-vmfest` can only use one communication method with
   VirtualBox, and once this method is set, it cannot be changed
   without restarting the JVM.

   In the provider configuration, `:vbox-comm` defaults to `:xpcom`,
   and it can be switched to `:ws` to use Web Services.

   VMs' hardware configuration
   ---------------------------

   The hardware model to be run by pallet can be defined in the node template or
   built from the template and a default model. The model will determine by the
   first match in the following options

   - The template has a `:hardware-model` entry with a vmfest hardware map.
     The VMs created will follow this model
         e.g. `{... :hardware-model {:memory-size 1400 ...}}`
   - The template has a `:hardware-id` entry. The value for this entry should
     correspond to an entry in the hardware-models map (or one of the entries
     that pallet offers by default.
         e.g. `{... :hardware-id :small ...}`
   - The template has no hardware entry. Pallet will use the first model
     in the hardware-models map to build an image that matches the rest of
     the relevant entries in the map.

   By default, pallet offers the following specializations of this base model:

       {:memory-size 512
        :cpu-count 1
        :storage [{:name \"IDE Controller\"
                   :bus :ide
                   :devices [nil nil nil nil]}]
        :boot-mount-point [\"IDE Controller\" 0]})

   The defined machines correspond to the above with some overrides:

       {:micro {:memory 512 :cpu-count 1}
        :small {:memory-size 1024 :cpu-count 1}
        :medium {:memory-size 2048 :cpu-count 2}
        :large {:memory-size (* 4 1024) :cpu-count 4}

   You can define your own hardware models that will be added to the default
   ones, or in the case that they're named the same, they will replace the
   default ones.  Custom models will also extend the base model above.

   Networking
   ----------

   Pallet offers two networking models: local and bridged.

   In Local mode pallet creates two network interfaces in the VM, one for an
   internal network (e.g. vboxnet0), and the other one for a NAT network. This
   option doesn't require VM's to obtain an external IP address, but requires
   the image booted to bring up at least eth0 and eth1, so this method won't
   work on all images.

   In Bridged mode pallet creates one interface in the VM that is bridged on a
   phisical network interface. For pallet to work, this physical interface must
   have an IP address that must be hooked in an existing network. This mode
   works with all images.

   The networking configuration for each VM created is determined by (in order):

   - the template contains a `:hardware-model` map with a `:network-type`
     entry
   - the template contains a `:network-type` entry
   - the service configuration contains a `:default-network-type` entry
   - `:local`

   Each networking type must attach to a network interface, be it local or
   bridged.  The decision about which network interface to attach is done in the
   following way (in order):

   - For bridged networking:
       - A `:default-bridged-interface` entry exists in the service definition
       - Pallet will try to find a suitable interface for the machine.
       - if all fails, VMs will fail to start
   - For local networking:
       - A `:default-local-interface` entry exists in the service definition
       - vboxnet0 (created by default by VirtualBox)

   Links
   -----

     - [VMFest](https://github.com/tbatchelli/vmfest)
     - [VirtualBox](https://virtualbox.org/)
     - [Pallet](http://palletops.com/)"
  (:require
    [clojure.tools.logging :as logging]
    [pallet.compute.implementation :as implementation]
    [dynapath.util :as dp]
    [clojure.java.io :refer [copy resource]])
  (:use
   [clojure.string :only [lower-case]]
   [vmfest.virtualbox.version :only [vbox-binding]]))

;; slingshot version compatibility
(try
  (use '[slingshot.slingshot :only [throw+ try+]])
  (catch Exception _
    (use '[slingshot.core :only [throw+ try+]])))


(defn add-image
  "Add an image to the images available. The image will be installed from the
   specified `url-string`."
  [compute url-string & {:as options}]
  (let [the-fn (ns-resolve 'pallet.compute.vmfest.service 'add-image)]
    (apply the-fn compute url-string options)))

;;;; Compute service SPI
(defn supported-providers []
  ["vmfest"])

;; lifted from alembic
(defn extract-jar
  "Extract a jar on the classpath to the filesystem, returning its URL."
  [^String jar-path]
  {:pre [(.endsWith jar-path ".jar")]}
  (let [jar-url (resource jar-path)
        f (java.io.File/createTempFile
           (subs jar-path 0 (- (count jar-path) 4)) ".jar")]
    (.deleteOnExit f)
    (with-open [is (.getContent jar-url)] (copy is f))
    (.toURL f)))



(defn add-vbox-to-classpath
  "If there is no vboxj*.jar library in the current classpath, it will
  add one based on the value of `comm`, that can be either :xpcom
  or :ws for XPCOM or Web Services.

  If the required library is already in the classpath, it will do
  nothing, and if the wrong library is already in the classpath, it'll
  thrown an exception."
  [comm]
  (condp = (vbox-binding)
    :xpcom (if (= comm :ws)
             (let [error-msg
                   (str "This VMFest provider is already configured to use XPCOM but "
                        "you are attempting to configure it to use Web Services. Only "
                        "one communication can be used at any time, and it can only "
                        "be set once per JVM run.")]
               (logging/error error-msg)
               (throw+
                {:type :vmfest-configuration-error
                 :message error-msg}))
             (logging/infof "This VMFest provider is already configured to use XPCOM."))
    :ws (if (= comm :xpcom)
          (let [error-msg
                (str "This VMFest provider is already configured to use Web Services but "
                     "you are attempting to configure it to use XPCOM. Only "
                     "one communication can be used at any time, and it can only "
                     "be set once per JVM run.")]
            (logging/error error-msg)
            (throw+
             {:type :vmfest-configuration-error
              :message error-msg}))
          (logging/infof "This VMFest provider is already configured to use Web Services.")) 
    :error (do
             (logging/infof "Connecting to VirtualBox via %s"
                            (if (= :ws comm) "Web Services" "XPCom"))
             (let [jar-path (if (= :ws comm)
                              "vboxjws-4.12.jar"
                              "vboxjxpcom-4.12.jar")
                   ;; The classloaders that come with the JVM cannot
                   ;; load a jar from within a jar. Instead, we'll
                   ;; copy the vboxj* jar from the pallet-vmfest jar
                   ;; into a temp location, and then add such location
                   ;; to the classloader. java, the things you make me
                   ;; do!
                   file-path (extract-jar jar-path)
                   ;; the classloader where to load this jar
                   cl  (or (.getClassLoader clojure.lang.RT)
                           (.getContextClassLoader (Thread/currentThread)))]
               (dp/add-classpath-url cl file-path)))))

;; A NOTE about this implementation
;;
;; This provider is implemented in such a way that the user can
;; determine the method of communication to be used with VirtualBox.
;; This is done by loading the right jar into the classpath, and this
;; decision can be done only once.
;;
;; For this to be possible no VMFest namespace can be loaded before
;; virtualbox jar file has been added to the classpath, otherwise
;; VMFest will fail. All the code that uses VMFest is now in
;; `pallet.compute.vmfest/service.clj` and the only references to
;; VMFest  in this file either happen after the library has been loaded
;; or reference namespaces within VMFest that don't require such jar
;; to be loaded. NOTE that this consideration is relevant both at
;; compile and run times.


(defmethod implementation/service :vmfest
  [_ {:keys [vbox-comm] :or {vbox-comm :xpcom} :as options}]
  (add-vbox-to-classpath vbox-comm)
  (require 'pallet.compute.vmfest.service)
  ((ns-resolve 'pallet.compute.vmfest.service 'vmfest-service-impl) _ options))
