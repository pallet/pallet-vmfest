# Release Notes

## 0.2.1

- Implement NodeHardware protocol

- Update to vmfest 0.2.5

- Add support for 0.8.0-SNAPSHOT post alpha.1


## 0.2.0

- Use the released version of vmfest 0.2.4.

- Disable pty for fedora
  Fedora seems to flush standard input when using a pty, and so
  intermittently misses the pallet scripts being sent to it.

- Allow override of destruction of vm on IP acquistion failure

- Allow image metadata to overide hardware model storage
  Images boot on either SATA or IDE but not both, so the storage device
  required on the hardware is image dependent.

- Allow specification of the :network-type on the image metadata
  Allows the image to specify it's preferred network connection type

## 0.2.0-beta.3

- Update to vmfest 0.2.4-beta.4

- Update to latest parent-pom

- Fix for protocol implementations that call each other
  This was broken by the wrapping of the nodes in a deftype

- Enable specification of packager in vmfest metadata

- Detect if the pallet version supports multi-lang scripts

- Add an add-vmfest-image task

- Remove debug print forms

## 0.2.0-beta.2

- Update vmfest dependency to 0.2.4-beta.3

- Allow selecting a vmfest image via :image-id. Fixes #2

- Wait for all network interfaces in a VM have an IP address before
  proceeding. Fixes #3

- Allow override of image destroy on error with :destroy-on-bootstrap-fail

- Fixes #1 : The host-only interface was not forwarded correctly to vmfest
  when using :local networking.

## 0.2.0-beta.1

Initial version.
