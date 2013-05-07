# pallet-vmfest

A provider for [Pallet][palletops], to use [vmfest][vmfest] to access
[virtualbox][virtualbox].

## Pallet

[Pallet][palletops] is used to provision and maintain servers on cloud and
virtual machine infrastructure, and aims to solve the problem of providing a
consistently configured running image across a range of clouds.  It is designed
for use from the [Clojure][clojure] REPL, from clojure code, and from the
command line.

- reuse configuration in development, testing and production.
- store all your configuration in a source code management system (eg. git),
  including role assignments.
- configuration is re-used by compostion; just create new functions that call
  existing crates with new arguments. No copy and modify required.
- enable use of configuration crates (recipes) from versioned jar files.

[Documentation][docs] is available.


## Installation

Pallet-vmfest is distributed as a jar, and is available in the
[clojars repository][sonatype].

Installation is with maven or your favourite maven repository aware build tool.

### lein project.clj

``` clojure
:dependencies [[com.palletops/pallet "0.8.0-beta.9"]
               [com.palletops/pallet-vmfest "0.3.0-alpha.3"]]
```

### maven pom.xml

``` xml
<dependencies>
  <dependency>
    <groupId>com.palletops</groupId>
    <artifactId>pallet</artifactId>
    <version>0.8.0-beta.9</version>
  </dependency>
  <dependency>
    <groupId>com.palletops</groupId>
    <artifactId>pallet-vmfest</artifactId>
    <version>0.3.0-alpha.3</version>
  </dependency>
<dependencies>
```

## Usage

### Prerequisites

Install
[VirtualBox 4.2.x (latest)](https://www.virtualbox.org/wiki/Downloads)
if you don't have it installed already. It won't work with older
versions of VirtualBox.

There are two ways in which `pallet-vmfest` communicates with
Virtualbox: __XPCOM__ and __Web Services__. XPCOM is faster and easier
to setup, but does not work on Windows (by design) and on some of the
latest versions of Linux distros. The Web Services method works
universally but requires a little bit of setup and a small server
running on your machine.

For XPCOM, there are no more prerequisites.

For Web Services you need to perform a one-time configuration of the
Web Services server named `vboxwebsrvr`:
 
```shell
$ VBoxManage setproperty websrvauthlibrary null
```

and then you always need to have `vboxwebsrvr` running when using
pallet-vmfest. This server can be started with:

```shell
$ vboxwebsrv -t0
```

### Defining the Compute Service to use with XPCOM

At the REPL you can define the VMFest/VirtualBox compute service the following
way:

``` clojure
(use '[pallet.configure :only [compute-service]])
(def vmfest (compute-service "vmfest"))
```

For a more permanent solution, define the VMFest/VirtualBox service by
adding a `:vmfest` service definition to your `~/.pallet/config.clj`
as shown here:

``` clojure
(defpallet :services {:vmfest {:provider "vmfest"}})
``` 

### Defining the Compute Service to use with Web Services

At the REPL use this:

```clojure
(use '[pallet.configure :only [compute-service]])
(def vmfest (compute-service "vmfest" :vbox-comm :ws))
```

And in `~/.pallet/config.clj` use:

``` clojure
(defpallet :services {:vmfest {:provider "vmfest"
                               :vbox-comm :ws}})
```

### Installing Images

Prior to using VMFest with Pallet for the first time, we need to setup
at least one model image:

```clojure
(use '[pallet.compute.vmfest :only [add-image]])
(add-image vmfest
  "https://s3.amazonaws.com/vmfest-images/ubuntu-12.04.vdi.gz")
```

You can verify that the image has been installed by running:
```clojure
(use '[pallet.compute :only [images]])
(pprint (images vmfest))
```

The new image is named `:ubuntu-12.04`.

## Using VMFest From Within Pallet

Since we just installed an image named `:ubuntu-12.04`,
we can proceed to use it by either referencing it directly:

```clojure
(use '[pallet.core :only [group-spec]])
(def ubuntu-group 
    (group-spec "ubuntu-vms" 
         :node-spec {:image {:image-id :ubuntu-12.04}}))
```

or by specifying an appropriate template, just as you would do with
any other cloud provider, e.g.:

```clojure
(use '[pallet.core :only [group-spec]])
(def ubuntu-group 
    (group-spec "ubuntu-vms" 
         :node-spec {:image {:image {:os-family :ubuntu      
                                     :os-64-bit? true }}}))
```

## Configuration

### Networking modes

Pallet-vmfest can work with 2 different network models

- __:local__: this is the default if no further configuration is
    specified. `:local` mode provides many advantages, but requires that
    the image used enables two network interfaces. In exchange, `:local`
    mode is more convenient in general than `:bridged` mode that we will
    discuss later:
    
    - VMs do not interact with external DHCP servers. This is relevant
      when repeatedly starting and destroying VMs. Home routers and
      office environments are not very happy when computers appear and
      disappear from the network over and over again.
    - VMs do not require valid IP addresses. Some IT environments have
      tight control on those.
    - VMs will continue to work if you switch networks. This is
      specially important for mobile workers. If you switch from your
      home to the coffee shop, your VMs will continue to work.
    - You can use many different internal networks, which can be
      useful when trying to emulate real network setups.
      
    On the other side, in this mode, VMs are not accessible from
    outside your host computer.

    By default pallet-vmfest will use :local mode, which is equivalent
    to adding the following entries to the vmfest provider definition
    in `~/.pallet/config.clj`:
    
    ```clojure
    :default-network-type :local
    :default-local-interface "vboxnet0"
    ```
    
    `:default-local-interface` determines what local-only network will
    be used (by default named `vboxnet0`, but you can use any as long
    as the naming complies with `vboxnetN`.    

- __:bridged__: In this mode, each VM will use one of the host's
    network interface directly to aquire a valid IP address in
    whichever network the interface is on. This means also that the
    selected host network interface needs to be on a valid network
    (i.e. it won't work if your computer is not plugged into any
    network) 

    Why would you want this networking mode?

    - One reason would be that you want your VMs to be acessible from
      other hosts.
    - The other reason is that you want to use VMFest to manage a
      remote host (which implies the reason above)

    There are a few drawbacks to this mode though:

    - Your home router will die. It happens. They're not created to
      see so much traffic of devices coming in and out of the network,
      especially if you use DHCP (which vmfest requires)
    - Your network administrator will get mad at you. "What are all
      these devices coming in an out of the network, all at once, over
      and over?!!"
    - If you don't have a valid network connection, you can't
      instantiate new VMs.
    - If you are mobile, when you switch networks your VMs will stop
      being reachable.

    To use `:bridged` mode, you need to add the following two entries
    in `~/.pallet.config.clj`, e.g.: 

    ```clojure
    :default-network-type :bridged
    :default-bridged-interface "en1: Wi-Fi (AirPort)"
    ```
   
    Notice that getting the name of the briged interface right is an
    art in itself. But this art can be reduced to technique if you run
    the following on your shell:

    ```bash
    $ VBoxManage list bridgedifs | grep ^Name
    ```
    
    You need to use the name of your inteface verbatim in
    `:default-bridged-interface`, orelse it won't work. And it doesn't
    matter how this interface is named anywhere else. All it matters
    is how VirtualBox sees it.


## License

Licensed under [EPL](http://www.eclipse.org/legal/epl-v10.html)

[Contributors](https://www.ohloh.net/p/pallet-clj/contributors)

Copyright 2010, 2011, 2012, 2013  Hugo Duncan and Antoni Batchelli


[palletops]: http://palletops.com "Pallet site"

[docs]: http://palletops.com/doc "Pallet Documentation"
[ml]: http://groups.google.com/group/pallet-clj "Pallet mailing list"
[basicdemo]: https://github.com/pallet/pallet-examples/blob/develop/basic/src/demo.clj "Basic interactive usage of Pallet"
[basic]: https://github.com/pallet/pallet-examples/tree/develop/basic/ "Basic Pallet Examples"
[screencast]: http://www.youtube.com/hugoduncan#p/u/1/adzMkR0d0Uk "Pallet Screencast"
[clojure]: http://clojure.org "Clojure"
[cljstart]: http://dev.clojure.org/display/doc/Getting+Started "Getting started with clojure"
[sonatype]: http://clojars.org/repo/com/palletops/ "Clojars Repository"

[vmfest]: https://github.com/tbatchelli/vmfest "vmfest"
[virtualbox]: http://virtualbox.org/ "VirtualBox"
