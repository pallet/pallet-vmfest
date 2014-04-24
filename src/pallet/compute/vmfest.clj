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

   The hardware model to be run by pallet is created via succesive merging
   of a default model, a specified hardware-id, hardware-model, and/or
   model template. The combined model is determined as follows:

   - Pallet will use the first model in the hardware-models map as a base
   - If the template has a `:hardware-id` entry, the value for this entry
     will be used to find a hardware model in the hardware-models map or
     one of the entries that pallet offers by default. When a model is found,
     it is merged over the base model above. The value of `:hardware-id` needs
     to be a string but will be converted to a keyword when used for lookup.
         e.g. `:hardware {... :hardware-id \"small\" ...}`
   - If the template has a `:hardware-model` entry with a vmfest hardware map,
     then the options specified will be merged over any previous settings
         e.g. `:hardware {... :hardware-model {:memory-size 1400 ...}}`
   - Finally, any of the following options specified in the hardware template
     will be merged 
         :min-ram   -> :memory-size
         :min-cores -> :cpu-count

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

   Pallet offers three networking models: local, nat, and bridged.

   In Local mode pallet creates two network interfaces in the VM, one for an
   internal network (e.g. vboxnet0), and the other one for a NAT network. This
   option doesn't require VM's to obtain an external IP address, but requires
   the image booted to bring up at least eth0 and eth1, so this method won't
   work on all images.

   In Nat mode pallet creates a single network interface in the VM for a NAT
   network. By default, the NAT service will be configured to forward the ssh
   port 22 on the VM to a free port on the host.

   In Bridged mode pallet creates one interface in the VM that is bridged on a
   physical network interface. For pallet to work, this physical interface must
   have an IP address that must be hooked in an existing network. This mode
   works with all images.

   The networking configuration for each VM created is determined by (in order):

   - the template contains a `:hardware-model` map with a `:network-type`
     entry
   - the template contains a `:network-type` entry
   - the service configuration contains a `:default-network-type` entry
   - `:local`

   Both local and nat networking types let you specify additional nat-rules which
   can be used to configure the VMs NAT service. Currently, this is limited to
   specifying port-forwarding rules.
       `:hardware {:hardware-model {:nat-rules
                                    [{:name \"http\", :protocol :tcp,
                                      :host-ip \"\", :host-port 8080,
                                      :guest-ip \"\", :guest-port 80}]}}

   The local and bridged networking types must attach to a network interface.
   The decision about which network interface to attach is done in the following
   way (in order):

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
    [pallet.compute.vmfest.protocols :as impl]
    [dynapath.util :as dp]
    [clojure.java.io :refer [copy resource]])
  (:use
   [clojure.string :only [lower-case]]
   [vmfest.virtualbox.version :only [vbox-binding]]))

(defn flatten-map [m]
  (mapcat identity m))

(defn add-image
  "Add an image to the images available. The image will be installed from the
   specified `url-string`."
  [compute url-string & {:as options}]
  (impl/install-image compute url-string options))

(defn find-images
  "Determine the best match image for a given image template"
  [service template]
  (impl/find-images service template))

(defn install-image
  "Install the image from the specified `url`"
  [service url & {:as options}]
  (impl/install-image service url (flatten-map options)))

(defn publish-image
  "Publish the image to the specified blobstore container"
  [service image blobstore container {:keys [path] :as options}]
  (impl/publish-image service image blobstore container options))

(defn has-image?
  "Predicate to test for the presence of a specific image"
  [service image-key]
  (impl/has-image? service image-key))

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
  (case (vbox-binding)
    :xpcom
    (if (= comm :ws)
      (let [error-msg
            (str "This VMFest provider is already configured to use XPCOM but "
                 "you are attempting to configure it to use Web Services. Only "
                 "one communication can be used at any time, and it can only "
                 "be set once per JVM run.")]
        (logging/error error-msg)
        (throw (ex-info
                error-msg
                {:type :vmfest-configuration-error
                 :message error-msg})))
      (logging/infof
       "This VMFest provider is already configured to use XPCOM."))

    :ws
    (if (= comm :xpcom)
      (let [error-msg
            (str
             "This VMFest provider is already configured to use Web Services "
             "but you are attempting to configure it to use XPCOM. Only "
             "one communication can be used at any time, and it can only "
             "be set once per JVM run.")]
        (logging/error error-msg)
        (throw (ex-info
                error-msg
                {:type :vmfest-configuration-error
                 :message error-msg})))
      (logging/infof
       "This VMFest provider is already configured to use Web Services."))

    :error
    (do
      (logging/infof "Connecting to VirtualBox via %s"
                     (if (= :ws comm) "Web Services" "XPCom"))
      (let [jar-path (if (= :ws comm)
                       "vboxjws-4.3.6.jar"
                       "vboxjxpcom-4.3.6.jar")
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
