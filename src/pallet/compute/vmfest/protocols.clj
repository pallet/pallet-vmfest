(ns pallet.compute.vmfest.protocols
  "Vmfest specific protocols")

(defprotocol ImageManager
  (install-image [service url {:as options}]
    "Install the image from the specified `url`")
  (publish-image [service image blobstore container {:keys [path] :as options}]
    "Publish the image to the specified blobstore container")
  (has-image? [service image-key]
    "Predicate to test for the presence of a specific image")
  (find-images [service template]
    "Determine the best match image for a given image template"))

(defprotocol VirtualBoxService
  (os-families [compute] "Return supported os-families")
  (medium-formats [compute] "Return supported medium-formats"))
