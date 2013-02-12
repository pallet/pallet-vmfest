(ns pallet.compute.vmfest
  "The VMFest provider allows Pallet to use VirtualBox via VMFest.

   Example Configuration
   ---------------------

   An example service configuration in `~/.pallet/config.clj`

       :vb {:provider \"vmfest\"
            :default-local-interface \"vboxnet0\"
            :default-bridged-interface \"en1: Wi-Fi 2 (AirPort)\"
            :default-network-type :local
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
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.tools.logging :as logging]
   [pallet.action-plan :as action-plan]
   [pallet.blobstore :as blobstore]
   [pallet.common.filesystem :as filesystem]
   [pallet.compute :as compute]
   [pallet.compute.implementation :as implementation]
   [pallet.compute.jvm :as jvm]
   [pallet.compute.vmfest.properties]   ; needs to be before vmfest
   [pallet.environment :as environment]
   [pallet.execute :as execute]
   [pallet.futures :as futures]
   [pallet.node :as node]
   [pallet.script :as script]
   [pallet.stevedore :as stevedore]
   [pallet.utils :as utils]
   [vmfest.manager :as manager]
   [vmfest.virtualbox.enums :as enums]
   [vmfest.virtualbox.image :as image]
   [vmfest.virtualbox.machine :as machine]
   [vmfest.virtualbox.medium :as medium]
   [vmfest.virtualbox.model :as model]
   [vmfest.virtualbox.session :as session]
   [vmfest.virtualbox.virtualbox :as virtualbox])
  (:use
   [clojure.string :only [lower-case]]))

;; slingshot version compatibility
(try
  (use '[slingshot.slingshot :only [throw+ try+]])
  (catch Exception _
    (use '[slingshot.core :only [throw+ try+]])))

;; feature predicates
(defmacro ^{:private true} get-has-feature
  []
  (try
    (do
      (require 'pallet.feature)
      (when-not (ns-resolve 'pallet.compute.vmfest 'has-feature?)
        (use '[pallet.feature
               :only [has-feature? when-feature when-not-feature]])))
    (catch Exception e
      `(do
         (defmacro ^{:private true} has-feature? [_#] false)
         (defmacro ^{:private true} when-not-feature [_# & ~'body]
           `(do ~@~'body))
         (defmacro ^{:private true} when-feature [_# & ~'body]
           nil)))))

(get-has-feature)

(defn has-pallet-ssh-execute?
  []
  (try
    (require 'pallet.ssh.execute)
    true
    (catch Exception e)))

(defmacro def-ssh-script-on-target
  []
  (if (has-pallet-ssh-execute?)
    `(use '[pallet.ssh.execute :only [~'ssh-script-on-target]])
    `(defn ssh-script-on-target
       [session# action# action-type# script#]
       {:pre [(-> session# :server :node)
              (ns-resolve '~'pallet.execute '~'remote-sudo)]}
       (let [os-family# (get-in session# [:server :image :os-family])]
         [((ns-resolve '~'pallet.execute '~'remote-sudo)
           (node/primary-ip (-> session# :server :node))
           script# (:user session#)
           {:pty (not= os-family# :fedora)})
          session#]))))

(def-ssh-script-on-target)

(defmacro def-make-user []
  (if (has-feature? core-user)
    '(defn make-user [username & {:as args}]
       (pallet.core.user/make-user username args))
    '(defn make-user [username & args]
       (apply pallet.utils/make-user username args))))

(def-make-user)

;; fallback os family translation data. This should be removed once
;; everyone is using metadata in their images.
(def os-family-name
  {:ubuntu "Ubuntu"
   :centos "RedHat"
   ;:rhel "RedHat"
   :rhel "RedHat_64"
   :debian "Debian_64"})

(def os-family-from-name
  (zipmap (vals os-family-name) (keys os-family-name)))

;;; names of tags used to tag the VMs once created
(def ip-tag "/pallet/ip")
(def group-name-tag "/pallet/group-name")
(def image-meta-tag "/pallet/image-meta")

(def
  ^{:doc "Determine what time of VM user session will be created by default:
 \"headless\", \"gui\" or \"sdl\":"
    :private true}
  default-vm-session-type "headless") ; gui, headless or sdl

(defn- image-meta-from-node
  "Obtains the image metadata from the node's extra parameters"
  [node]
  (when-let [meta-str (manager/get-extra-data node image-meta-tag)]
    (when-not (empty? meta-str)
      (with-in-str meta-str (read)))))

(defn- node-accessible?
  "True if the node (vbox Machine) is accessible, false otherwise.

Accessible means that VirtualBox itself can access the machine. In
  cases in which the Machine is malformed or files are missingn the VM
  is inaccessible. VBox cannot do much with an inaccessible machine,
  other than list it, and maybe destroy it."
  [node]
  (session/with-no-session node [m]
    (machine/get-attribute m :accessible?)))

(defn- node-running?
  [node]
  (and
     (node-accessible? node)
     (= :running (manager/state node))))

(deftype VmfestNode
    [^vmfest.virtualbox.model.Machine node service]
  pallet.node/Node
  (ssh-port [_] 22)
  (primary-ip [_]
    (try+
      (manager/get-ip node)
      (catch Exception _
        ;; fallback to the ip stored in the node's extra parameters
        (manager/get-extra-data node ip-tag))))
  (private-ip [_] nil)
  (is-64bit? [_]
    (let [meta (image-meta-from-node node)
          os-64-bit (:os-64-bit meta)
          os-type-id (:os-type-id meta)]
      ;; either :os-64-bit is present, or we get it from :os-type-id
      ;; if present, or we try to guess from the VM itself
      (or (or os-64-bit
              (when os-type-id
                (boolean (re-find #"_64" os-type-id))))
          ;; try guessing it from the VBox Machine object. This should
          ;; not be necessary in the near future. Remove after 0.3
          (do
            (logging/warnf
             "Cannot determine if machine is 64 bit from metadata: '%s'" meta)
            (if-let [os-type-id (session/with-no-session
                                  node [m] (.getOSTypeId m))]
              (boolean (re-find #"_64" os-type-id))
              (logging/error
               "Cannot determine if machine is 64 bit by any means."))))))
  (group-name [_]
    (let [group-name (manager/get-extra-data node group-name-tag)]
      (if (string/blank? group-name)
        (manager/get-extra-data node group-name-tag)
        group-name)))
  (hostname [_]
    (session/with-no-session node [m]
      (.getName m)))
  (os-family [_]
    (let [meta (image-meta-from-node node)
          os-family-from-meta (:os-family meta)]
      (if os-family-from-meta
        os-family-from-meta
        ;; try guessing it from the VBox Machine object. This should
        ;; not be necessary in the near future. Remove after 0.3
        (do
          (logging/warnf
           "Cannot get os-family from node's metadata '%s'. Trying to guess"
           meta)
          (let [os-name (session/with-no-session node [m] (.getOSTypeId m))]
            (or (os-family-from-name os-name os-name)
                :centos) ;; todo: remove this hack!
            )))))
  (os-version [_]
    (let [meta (image-meta-from-node node)]
      (or
       (:os-version meta))))
  (running? [_]
    (node-running? node))
  (terminated? [_] false)
  (id [_] (:id node))
  (compute-service [_] service))

(when-feature node-packager
  (extend-type VmfestNode
    pallet.node/NodePackager
    (packager [n]
      (compute/packager-for-os (node/os-family n) (node/os-version n)))))

(when-feature node-image
  (extend-type VmfestNode
    pallet.node/NodeImage
    (image-user [node]
      (let [meta (image-meta-from-node (.node node))
            username (:username meta)]
        (when username
          (utils/apply-map
           make-user
           username
           (merge
            {:public-key-path nil :private-key-path nil}
            (select-keys meta [:password :sudo-password :no-sudo]))))))))

(def enum-to-kw (comp keyword lower-case str))
(defmacro doall->> [& args]
  `(doall (->> ~(last args) ~@(butlast args))))

(when-feature node-hardware
  (extend-type VmfestNode
    pallet.node/NodeHardware
    (hardware [node]
      (session/with-session (.node node) :shared [session m]
        {:cpus [{:cores (machine/get-attribute m :cpu-count)}]
         :ram (machine/get-attribute m :memory-size)
         :disks (->>
                 (machine/get-attribute m :medium-attachments)
                 (map bean)
                 (map #(dissoc % :class :passthrough))
                 (doall->>
                  (map
                   (fn [a]
                     (let [a (update-in
                              a [:medium]
                              (fn [medium]
                                (let [m (medium/map-from-IMedium medium nil)]
                                  (-> m
                                      (dissoc :children :parent :base :type)
                                      (update-in
                                       [:medium-format]
                                       (comp keyword lower-case #(.getName %)))
                                      (assoc :medium-type
                                        (enum-to-kw (:type m)))
                                      (assoc :size (:logical-size m (:size m)))
                                      (assoc :physical-size (:size m))
                                      (update-in [:device-type] enum-to-kw)
                                      (update-in [:state] enum-to-kw)
                                      (update-in [:format] enum-to-kw)
                                      (update-in [:machine-ids]
                                                 #(vec (map identity %)))))))]
                       (merge (:medium a)
                              (-> a
                                  (dissoc :medium :nonRotational)
                                  (assoc :ssd (:nonRotational a))))))))
                 (map (fn [m]
                        (update-in
                         m [:type]
                         (comp keyword lower-case #(.name %)))))
                 vec)}))))

(when-feature node-proxy
  (extend-type VmfestNode
    pallet.node/NodeProxy
    (proxy [node]
        (let [n (.node node)
              proxy (->>
                     (manager/get-network-adapters n)
                     (mapcat #(get-in % [:nat :forwards]))
                     (filter #(= (str (node/ssh-port node)) (:guest-port %)))
                     first)
              port (:host-port proxy)]
          (when-not (string/blank? port)
            (try
              {:port (Integer/parseInt port)
               :host (or (:host-ip proxy) "127.0.0.1")}
              (catch Exception e
                (logging/warnf
                 e "Could not decode host port for proxy from %s"
                 port))))))))

(defn- nil-if-blank [x]
  (if (string/blank? x) nil x))

(defn- current-time-millis []
  (System/currentTimeMillis))

(defn- wait-for-ip
  "Wait for the machines IP to become available by the provided amount of
  milliseconds, or 5min by default."
  ;; TODO: find a way to signal when an ip is not available
  ([machine slot] (wait-for-ip machine slot 300000))
  ([machine slot timeout]
     (logging/debugf "Waiting %s ms for the IP in slot %s of %s to come up."
                     timeout slot (manager/get-machine-attribute machine :name))
     (let [timeout (+ (current-time-millis) timeout)]
       (loop []
         (let [ip (try (manager/get-ip machine :slot slot)
                       (catch RuntimeException e
                         (logging/warnf e
                          "wait-for-ip: Machine %s not started yet..."
                          machine))
                       (catch Exception e
                         (logging/warnf e
                          "wait-for-ip: Machine %s is not accessible yet..."
                          machine)))]
           (if (and (string/blank? ip) (< (current-time-millis) timeout))
             (do
               (Thread/sleep 2000)
               (recur))
             ip))))))


(defn- machine-name
  "Generate a machine name based on the grup name and an index"
  [group-name n]
  (format "%s-%s" group-name n))

(defprotocol VirtualBoxService
  (os-families [compute] "Return supported os-families")
  (medium-formats [compute] "Return supported medium-formats"))

(defn- node-data
  "data about a running node: name, description, session state,
  ip address and group to which it belongs.

  returns: [name description session state] {:ip ip :group-name group}*

  (*) only if the machine is accessible."
  [m]
  (let [attributes (session/with-no-session m [im]
                     [(.getName im)
                      (.getDescription im)
                      (.getSessionState im)])
        open? (= :open
                 (session/with-no-session m [im]
                   (enums/session-state-to-key
                    (.getSessionState im))))
        ip (when open? (manager/get-ip m))
        group-name (when open? (manager/get-extra-data m group-name-tag))]
    (into attributes {:ip ip :group-name group-name}) ))

(defn- node-infos [compute-service]
  (let [nodes (manager/machines compute-service)]
    (map node-data nodes)))

(defn- connected-network-slots
  "Returns the slot numbers in the hardware model that are to be connected to a
 network"
  [hardware-model]
  (let [network-config (:network hardware-model)
        numbered-slots (partition 2 (interleave (range (count network-config))
                                         network-config))]
    (filter (comp not nil?)
            (for [[slot-num slot] numbered-slots]
              (if (and (not (nil? slot))
                       (not (= false (:enabled slot)))
                       (not (= false (:cable-connected slot))))
                slot-num
                nil)))))

(defn- wait-for-all-ips
  "Waits the interfaces in the network slots to acquire an IP address.
  If any of them fails to do so, it will return false, otherwise it
  will return true"
  [machine slots]
  (let [ip-seq
        (doall (map #( wait-for-ip machine %) slots))]
    (empty? (filter string/blank? ip-seq))))

(defmacro declare-bootstrap-via-ssh
  []
  (if (has-feature? multilang-script)
    `(defn- bootstrap-via-ssh
      [~'image ~'node ~'user ~'init-script]
      (ssh-script-on-target
       {:server {:node ~'node :image ~'image} :user ~'user}
       {:node-value-path (gensym "vmfest")}
       :script
       [{:language :bash} ~'init-script]))
    `(defn- bootstrap-via-ssh
      [~'image ~'node ~'user ~'init-script]
      (ssh-script-on-target
       {:server {:node ~'node :image ~'image} :user ~'user}
       {:node-value-path (gensym "vmfest")}
       :script/bash
       ~'init-script))))

(declare-bootstrap-via-ssh)

(defn- create-node
  "Instantiates a compute node on vmfest and runs the supplied init script.

  The node will be named 'machine-name', and will be built according
  to the supplied 'model'. This node will boot from the supplied
  'image' and will belong to the supplied 'group' "
  [compute-service server node-path node-spec machine-name image model
   group-name init-script user]
  (logging/tracef
   "Creating node from image: %s and hardware model %s" image model)
  (let [machine (manager/instance*
                 server machine-name image model node-path)]
    (manager/set-extra-data machine image-meta-tag (pr-str image))
    (manager/set-extra-data machine group-name-tag group-name)
    (manager/start
     machine
     :session-type (or ;; todo: move this decision upstream, when it
                    ;; is first possible.
                    (get-in node-spec [:image :session-type])
                    default-vm-session-type))
    ;;    (logging/trace "Wait to allow boot")
    ;;    (Thread/sleep 15000)                ; wait minimal time for vm to boot
    (logging/trace "Waiting for ip")
    ;; TODO: Pass the slots to look for ips...
    ;; TODO: Figure out how to determine if tetting the ip has been
    ;; successful.
    (let [connected-slots (connected-network-slots model)]
      (when (and (not (wait-for-all-ips machine connected-slots))
                 (:destroy-on-ip-fail node-spec true))
        (manager/destroy machine)
        (throw+
         {:type :no-ip-available
          :message "Could not determine IP address of new node"})))
    ;; wait for services to come up, specially SSH
    ;; todo: provide some form of exponential backoff try with at
    ;; something like 3 attempts. A single wait for 4s might not
    ;; work under high contention (e.g. starting many nodes)
    (Thread/sleep 4000)
    (let [node (VmfestNode. machine compute-service)]
      (when-not-feature run-nodes-without-bootstrap
        (when-not (string/blank? init-script)
          (logging/infof "Bootstrapping %s" (manager/get-ip machine))
          (script/with-script-context
            (action-plan/script-template-for-server {:image image})
            (stevedore/with-script-language :pallet.stevedore.bash/bash
              (let [user (if (:username image)
                           (pallet.utils/make-user
                            (:username image)
                            :password (:password image)
                            :no-sudo (:no-sudo image)
                            :sudo-password (:sudo-password image))
                           user)
                    [{:keys [out exit]} session] (bootstrap-via-ssh
                                                  image node user init-script)]
                (when-not (zero? exit)
                  (when (:destroy-on-bootstrap-fail node-spec true)
                    (manager/destroy machine))
                  (throw+
                   {:message (format "Bootstrap failed: %s" out)
                    :type :pallet/bootstrap-failure
                    :group-spec node-spec})))))))
      node)))

(defn- always-match
  [image-properties kw arg]
  true)

(defn- equality-match
  [image-properties kw arg]
  (logging/tracef
   "matching %s=%s for %s in %s" (image-properties kw) arg kw image-properties)
  (= (image-properties kw) arg))

(defn- regexp-match
  [image-properties kw arg]
  (when-let [value (image-properties kw)]
    (logging/tracef
     "matching %s=%s for %s in image: %s" kw value arg image-properties)
    (re-matches (re-pattern arg) value)))

(def
  ^{:doc "Maps the template field with a function that will determine if the
          template matches."
    :private true}
  template-matchers
  {:os-version-matches (fn [image-properties kw arg]
                         (regexp-match image-properties :os-version arg))
   :image-name-matches (fn [image-properties kw arg]
                         (regexp-match image-properties :image-name arg))
   :image-id-matches (fn [image-properties kw arg]
                       (regexp-match image-properties :image-id arg))
   :image-description-matches (fn [image-properties kw arg]
                                (regexp-match
                                 image-properties :description arg))
   :os-family (fn [image-properties kw arg]
                (equality-match image-properties :os-family arg))
   :os-64-bit (fn [image-properties kw arg]
                (equality-match image-properties :os-64-bit arg))})

(defn- all-images-from-template
  "Finds all the images that match a template"
  [images template]
  (->>
   images
   (filter
    (fn image-matches? [[image-name image-properties]]
      ;; check wether all the template matchers present in the
      ;; template match and defined in 'template-matchers' match
      ;; with the image
      (every?
       ;; either the key in the template has a value in
       ;; 'template-matchers' or the template will always match,
       ;; thus ignoring the key. Basically only try to match for
       ;; entries in the template that have a matcher and ignore
       ;; the rest.
       #(or
         (((first %) template-matchers always-match)
          image-properties (first %) (second %))
         (logging/debugf
          "Template match failed for image %s on %s"
          (:image-id image-properties) (first %)))
       template)))
   (into {})))

(defn- image-from-template
  "Use the template to select an image from the image map."
  [images template]
  (logging/debugf "Looking for %s in %s" template images)
  (if-let [image-id (:image-id template)]
    (keyword image-id)
    (ffirst (all-images-from-template images template))))

(defn serial-create-nodes
  "Create all nodes for a group in parallel."
  [target-machines-to-create compute-service server node-path node-spec image
   machine-model group-name init-script user]
  (doall
   (for [name target-machines-to-create]
     (create-node
      compute-service server node-path node-spec name image machine-model
      group-name init-script user))))

(defn parallel-create-nodes
  "Create all nodes for a group in parallel."
  [target-machines-to-create compute-service server node-path node-spec image
   machine-model group-name init-script user]
  ;; the doseq ensures that all futures are completed before
  ;; returning
  (->>
   (for [name target-machines-to-create]
     (future
       (create-node
        compute-service server node-path node-spec name image machine-model
        group-name init-script user)))
   doall ;; doall forces creation of all futures before any deref
   futures/add
   (map #(futures/deref-with-logging % "Start of node"))
   (filter identity)
   doall))

(def
  ^{:dynamic true
    :doc "The buffer size (in bytes) for the piped stream used to implement
    the :stream option for :out. If your ssh commands generate a high volume of
    output, then this buffer size can become a bottleneck. You might also
    increase the frequency with which you read the output stream if this is an
    issue."}
  *piped-stream-buffer-size* (* 1024 1024))

(defn- piped-streams
  []
  (let [os (java.io.PipedOutputStream.)]
    [os (java.io.PipedInputStream. os *piped-stream-buffer-size*)]))

(defn- gzip [from to]
  (with-open [input (io/input-stream from)
              output (java.util.zip.GZIPOutputStream. (io/output-stream to))]
    (io/copy input output)))

(defprotocol ImageManager
  (install-image [service url {:as options}]
    "Install the image from the specified `url`")
  (publish-image [service image blobstore container {:keys [path] :as options}]
    "Publish the image to the specified blobstore container")
  (has-image? [service image-key]
    "Predicate to test for the presence of a specific image")
  (find-images [service template]
    "Determine the best match image for a given image template"))

(defn- hardware-model-from-template [model template network-type interface]
  (merge model
         {:memory-size (or (:min-ram template)
                           (:memory-size model))
          :cpu-count (or (:min-cores template)
                         (:cpu-count model))
          :network-type network-type
          ;; todo: allow overriding the interface here
          }))

(defn find-free-port
  "Find a free port.  Note that this will not prevent conflicts. It's just a
   way of reducing them."
  []
  (try
    (let [socket (java.net.ServerSocket. 0)]
      (try
        (.getLocalPort (doto socket (.setReuseAddress true)))
        (finally
          (when socket (.close socket)))))
    (catch Exception _
      (+ 1024 (rand-int 60000)))))


(defn- selected-hardware-model
  [{:keys [hardware-id hardware-model] :as template} models storage-overide
   default-network-type default-nat-rules default-local-interface
   default-bridged-interface]
  (let [model
        (cond
          ;; if a model is specified, we take it
          hardware-model (merge (second (first models)) hardware-model)
          ;; if not, is a model key provided?
          hardware-id (hardware-id models)
          ;; we'll build the model from the template then.
          :else (hardware-model-from-template
                 ;; use the first model in the list
                 (second (first models))
                 template
                 default-network-type
                 ;; pass the right interface for network-type
                 (if (= :local default-network-type)
                   default-local-interface
                   default-bridged-interface)))
        ;; if no network-type is speficied at this point, use the
        ;; default
        network-type (or (:network-type model) default-network-type)]
    (merge (dissoc model :network-type)
           storage-overide
           ;; add the right network interface configuration for the final
           ;; network-type
           {:network (cond
                       ;; local networking
                       (= network-type :local)
                       [{:attachment-type :host-only
                         :host-only-interface default-local-interface}
                        {:attachment-type :nat}]
                       ;; nat only networking
                       (= network-type :nat)
                       [{:attachment-type :nat
                         :nat-rules
                         (conj
                          default-nat-rules
                          {:name "ssh"
                           :protocol :tcp
                           :host-ip "" :host-port (find-free-port)
                           :guest-ip "" :guest-port 22})}]
                       ;; bridged networking
                       :else
                       [{:attachment-type :bridged
                         :host-interface default-bridged-interface}])})))

(defn image-template-from-group-spec [group-spec]
  (->> [:image :hardware :location :network :qos]
       (select-keys group-spec) vals (reduce merge)))


(defn node-shutdown [machine]
  (manager/power-down machine)
  (if-let [state (manager/wait-for-machine-state
                  machine [:powered-off] 300000)]
    (logging/debugf "Machine state is %s" state)
    (logging/warn "Failed to wait for power down completion"))
  (manager/wait-for-lockable-session-state machine 2000))

(defn node-destroy [machine]
  (node-shutdown machine)
  (manager/destroy machine))

(defn- accessible-machines
  "Returns the VMs that are accessible"
  [server]
  (filter node-accessible? (manager/machines server)))

(deftype VmfestService
    [server images locations network-type local-interface bridged-interface
     environment models tag-provider]
  pallet.compute/ComputeService
  (nodes [compute-service]
    ;; we only want to return accessible machines. Pallet is expecting
    ;; to be able to ask questions about the nodes, for example the
    ;; group they belong, or their IP addresss. This would fail if the
    ;; machine is not accessible.
    ;; NOTE: this means that Pallet is effectively unable to manage
    ;; inaccessible machines.
    (map #(VmfestNode. % compute-service) (accessible-machines server)))

  (ensure-os-family [compute-service group-spec]
    (logging/debugf "ensure-os-family called with group-spec = %s" group-spec)
    ;; if we are looking for a particular image via image-id, then we
    ;; need to fetch the meta of that image and merge its contents
    ;; with the contents of the template.
    (let [template (image-template-from-group-spec group-spec)
          image (or (image-from-template
                     @images template)
                    (throw (RuntimeException.
                            (format
                             "No matching image for %s in %s (using %s)"
                             (pr-str (:image group-spec))
                             @images
                             locations))))]
      (update-in
       group-spec [:image]
       #(merge
         {:image-id image}
         (select-keys
          (@images image)
          [:os-family :os-version :os-64-bit :packager])
         %))))

  (run-nodes
    [compute-service group-spec node-count user init-script options]
    (try
      (let [template (image-template-from-group-spec group-spec)
            _ (logging/debugf "run-nodes with template %s" template)
            image (or (image-from-template
                       @images template)
                      (throw (RuntimeException.
                              (format
                               "No matching image for %s in %s (using %s)"
                               (pr-str (:image group-spec))
                               @images
                               locations))))
            group-name (name (:group-name group-spec))
            machines (accessible-machines server)
            current-machines-in-group (filter
                                       #(= group-name
                                           (manager/get-extra-data
                                            % group-name-tag))
                                       machines)
            current-machine-names (into #{}
                                        (map
                                         #(session/with-no-session % [m]
                                            (.getName m))
                                         current-machines-in-group))
            target-indices (range (+ node-count
                                     (count current-machines-in-group)))
            target-machine-names (into #{}
                                       (map
                                        #(machine-name group-name %)
                                        target-indices))
            target-machines-already-existing (clojure.set/intersection
                                              current-machine-names
                                              target-machine-names)
            target-machines-to-create (clojure.set/difference
                                       target-machine-names
                                       target-machines-already-existing)
            create-nodes-fn (get-in environment
                                    [:algorithms :vmfest :create-nodes-fn]
                                    parallel-create-nodes)
            image-map (image @images)
            network-type (or (:network-type (image @images)) network-type)
            nat-rules (:nat-rules (image @images))
            image=hardware-overrides ()
            final-hardware-model (selected-hardware-model
                                  template
                                  models
                                  (select-keys
                                   image-map [:storage :boot-mount-point])
                                  network-type
                                  nat-rules
                                  local-interface
                                  bridged-interface)]
        (logging/debug (str "current-machine-names " current-machine-names))
        (logging/debug (str "target-machine-names " target-machine-names))
        (logging/debug (str "target-machines-already-existing "
                            target-machines-already-existing))
        (logging/debug (str "target-machines-to-create"
                            target-machines-to-create))
        (logging/debugf "Selected image: %s" image)
        (logging/debugf "Hardware model %s" final-hardware-model)
        (create-nodes-fn
          target-machines-to-create
          compute-service
          server
          (:node-path locations)
          group-spec
          (image @images)
          final-hardware-model
          group-name
          init-script user))))

  (reboot
    [compute nodes]
    (doseq [node nodes]
      (node-shutdown node)
      (manager/start node)))

  (boot-if-down
    [compute nodes]
    (doseq [node nodes]
      (manager/start node)))

  (shutdown-node
    [compute node _]
    ;; todo: wait for completion
    (logging/infof "Shutting down %s" (pr-str node))
    (let [machine (.node node)]
      (node-shutdown machine)))

  (shutdown
    [compute nodes user]
    (doseq [node nodes]
      (node-shutdown node)))

  (destroy-nodes-in-group
    [compute group-name]
    (let [nodes (locking compute ;; avoid disappearing machines
                  (vec
                   (filter
                    #(and
                      (node-running? %)
                      (= group-name (manager/get-extra-data % group-name-tag)))
                    (manager/machines server))))]
      (doseq [machine nodes]
        (node-destroy machine))))

  (destroy-node
    [compute node]
    {:pre [node]}
    (node-destroy (.node node)))

  (images [compute]
    @images)

  (close [compute])
  pallet.environment.Environment
  (environment [_] environment)
  ImageManager
  (install-image
    [compute url {:as options}]
    (logging/debugf "installing image to %s with options %s"
                    (:model-path locations) options)
    (when-let [job (apply
                    image/setup-model
                    url server :models-dir (:model-path locations)
                    (apply concat options))]
      (swap! images merge (:meta job))))
  (publish-image [service image-kw blobstore container {:keys [path]}]
    (if-let [image (image-kw @images)]
      (session/with-vbox server [_ vbox]
        (let [medium (virtualbox/find-medium vbox (:uuid image))
              file (java.io.File. (.getLocation medium))]
          (filesystem/with-temp-file [gzip-file]
            (logging/debugf "gzip to %s" (.getPath gzip-file))
            (gzip file gzip-file)
            (logging/debugf "put gz %s" (.getPath gzip-file))
            (try
              (blobstore/put
               blobstore container (or path (str (.getName file) ".gz"))
               gzip-file)
              (catch Exception e
                (logging/error e "Upload failed"))))
          (logging/info "put meta")
          (blobstore/put
           blobstore container
           (string/replace (or path (.getName file)) #"\.vdi.*" ".meta")
           (pr-str {image-kw (dissoc image :uuid)}))))
      (let [msg (format
                 "Could not find image %s. Known images are %s."
                 image-kw (keys @images))]
        (logging/error msg)
        (throw+
         {:type :pallet/unkown-image
          :image image-kw
          :known-images (keys @images)
          :message msg}))))
  (has-image? [_ image-kw]
    ((or @images {}) image-kw))
  (find-images [_ template]
    (all-images-from-template @images template)))

(when-feature compute-service-properties
  (extend-type VmfestService
    pallet.compute/ComputeServiceProperties
    (service-properties [compute]
      (assoc (bean compute) :provider :vmfest))))

(defmacro add-node-tag []
  (if (has-feature? taggable-nodes)
    '(do
       (deftype ExtraDataNodeTag []
         pallet.compute.NodeTagReader
         (node-tag [_ node tag-name]
           (manager/get-extra-data (.node node) tag-name))
         (node-tag [_ node tag-name default-value]
           (let [value (manager/get-extra-data (.node node) tag-name)]
             (if (string/blank? value)
               (let [keys (manager/get-extra-data-keys (.node node))]
                 (if (seq (filter #(= tag-name %) keys))
                   value
                   default-value))
               value)))
         (node-tags [_ node]
           (into {}
                 (map
                  #(vector % (manager/get-extra-data (.node node) %))
                  (manager/get-extra-data-keys (.node node)))))
         pallet.compute.NodeTagWriter
         (tag-node! [_ node tag-name value]
           (manager/set-extra-data (.node node) tag-name value))
         (node-taggable? [_ node] true))
       (extend-type VmfestService
         pallet.compute/NodeTagReader
         (node-tag
           ([compute node tag-name]
              (compute/node-tag
               (.tag_provider compute) node tag-name))
           ([compute node tag-name default-value]
              (compute/node-tag
               (.tag_provider compute) node tag-name default-value)))
         (node-tags [compute node]
           (compute/node-tags (.tag_provider compute) node))
         pallet.compute/NodeTagWriter
         (tag-node! [compute node tag-name value]
           (compute/tag-node! (.tag_provider compute) node tag-name value))
         (node-taggable? [compute node]
           (compute/node-taggable? (.tag_provider compute) node)))
       (defn default-tag-provider [] (ExtraDataNodeTag.)))
    `(defn ~'default-tag-provider [] nil)))

(add-node-tag)

(defn add-image
  "Add an image to the images available. The image will be installed from the
   specified `url-string`."
  [compute url-string & {:as options}]
  (install-image compute url-string options))

(def base-model
  {:memory-size 512
   :cpu-count 1
   :storage [{:name "IDE Controller"
              :bus :ide
              :devices [nil nil nil nil]}]
   :boot-mount-point ["IDE Controller" 0]})

(def default-models
  {:micro base-model
   :small (merge base-model {:memory-size 1024 :cpu-count 1})
   :medium (merge base-model {:memory-size 2048 :cpu-count 2})
   :large (merge base-model {:memory-size (* 4 1024) :cpu-count 4})})

(defn- process-images
  "preformats the image maps to complete some of the fields"
  [images]
  (zipmap (keys images)
          (map (fn [[k v]] (assoc v
                            :image-name (name k)
                            :image-id (name k)))
               images)))

;;;; Compute service SPI
(defn supported-providers []
  ["vmfest"])

(defmethod implementation/service :vmfest
  [_ {:keys [url identity credential images node-path model-path locations
             environment default-network-type default-bridged-interface
             default-local-interface hardware-models tag-provider]
      :or {url "http://localhost:18083/"
           identity "test"
           credential "test"
           model-path (manager/default-model-path)
           node-path (manager/default-node-path)
           default-network-type :local}
      :as options}]

  (let [locations (or locations
                      {:local {:node-path node-path :model-path model-path}})
        _ (logging/debugf "Loading images from %s" model-path)
        images (merge (manager/load-models :model-path model-path) images)
        _ (logging/debugf "Loaded images %s" (keys images))
        images (process-images images)
        models (merge default-models
                      ;; new hardware models inherit from the
                      ;; base-model
                      (zipmap (keys hardware-models)
                              (map
                               (partial merge base-model)
                               (vals hardware-models))))
        available-host-interfaces (manager/find-usable-network-interface
                                   (manager/server url identity credential))
        bridged-iface (or
                       default-bridged-interface
                       (do
                         (logging/info
                          (str
                           "No :default-bridged-interface defined. "
                           "Will chose from these options: "
                           (apply
                            str (interpose ", " available-host-interfaces))))
                         (first available-host-interfaces)))
        local-iface (or default-local-interface
                        (do
                          (logging/debug
                           "No Local Interface defined. Using vboxnet0")
                          "vboxnet0"))] ;; todo. Automatically discover this
    (logging/debugf "Loaded images: %s" (keys images))
    (logging/debugf
     "Using '%s' networking via interface '%s' as defaults for new machines"
     (name default-network-type)
     (if (= default-network-type :local)
       local-iface
       bridged-iface))
    (doseq [[name model] models]
      (logging/debugf "loaded model %s = %s" name model))
    (VmfestService.
     (vmfest.virtualbox.model.Server. url identity credential)
     (atom images)
     (val (first locations))
     default-network-type
     local-iface
     bridged-iface
     environment
     models
     (or tag-provider (default-tag-provider)))))

(defmethod clojure.core/print-method VmfestNode
  [node writer]
  (let [machine (.node node)
        accessible (try (session/with-vbox (:server machine) [_ vbox]
                          (node-accessible? machine))
                        (catch Exception _))]
    (cond
      (not accessible)
      (.write writer "Unaccessible vmfest node")

      (not
       (->>
        (compute/nodes (.service node))
        (some #(= (:id machine) (:id (.node %))))))
      (.write writer "Unregistered vmfest node")

      :else
      (.write
       writer
       (format
        "%14s\t %14s\t public: %s"
        (try (node/hostname node) (catch Throwable e "unknown"))
        (try (node/group-name node) (catch Throwable e "unknown"))
        (try (node/primary-ip node) (catch Throwable e "unknown")))))))
