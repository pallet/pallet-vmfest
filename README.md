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


## Installation for VirtualBox 4.2.x

Pallet-vmfest is distributed as a jar, and is available in the
[sonatype repository][sonatype].

Installation is with maven or your favourite maven repository aware build tool.

### lein project.clj

``` clojure
:dependencies [[org.cloudhoist/pallet "0.7.2"]
               [org.cloudhoist/pallet-vmfest "0.2.4"]]
:repositories {"sonatype"
               "http://oss.sonatype.org/content/repositories/releases"}
```

### maven pom.xml

``` xml
<dependencies>
  <dependency>
    <groupId>org.cloudhoist</groupId>
    <artifactId>pallet</artifactId>
    <version>0.7.2</version>
  </dependency>
  <dependency>
    <groupId>org.cloudhoist</groupId>
    <artifactId>pallet-vmfest</artifactId>
    <version>0.2.4</version>
  </dependency>
<dependencies>

<repositories>
  <repository>
    <id>sonatype</id>
    <url>http://oss.sonatype.org/content/repositories/releases</url>
  </repository>
</repositories>
```

### Installation for VirtualBox 4.1.x

The version `0.2.1` of `pallet-vmfest` supports VirtualBox 4.1.x only.

## Usage

### Prerequisites

Follow [these
instructions](https://github.com/tbatchelli/vmfest#install-virtualbox-42x)
to install and setup VirtualBox to work with VMFest (and Pallet).

### Defining the Compute Service

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

### Installing Images

Prior to using VMFest with Pallet for the first time, we need to setup
at least one model image:

```clojure
(use '[pallet.compute.vmfest :only [add-image]])
(add-image vmfest
  "https://s3.amazonaws.com/vmfest-images/debian-6.0.2.1-64bit-v0.3.vdi.gz")
```

You can verify that the image has been installed by running:
```clojure
(use '[pallet.compute :only [images]])
(pprint (images vmfest))
```

The new image is named `:debian-6.0.2.1-64bit-v0.3`.

## Using VMFest From Within Pallet

Since we just installed an image named `:debian-6.0.2.1-64bit-v0.3`,
we can proceed to use it by either referencing it directly:

```clojure
(use '[pallet.core :only [group-spec]])
(def debian-group 
    (group-spec "debian-vms" 
         :node-spec {:image {:image-id :debian-6.0.2.1-64bit-v0.3}}))
```

or by specifying an appropriate template, just as you would do with
any other cloud provider, e.g.:

```clojure
(use '[pallet.core :only [group-spec]])
(def debian-group 
    (group-spec "debian-vms" 
         :node-spec {:image {:image {:os-family :debian      
                                     :os-64-bit? true }}}))
```

## Configuration

### Networking modes

Pallet-vmfest can work with 2 different network models

- __:local__: this is the default if no further configuration is
    specified. :local mode provides many advantages, but requires that
    the image used enables two network interfaces. In exchange, :local
    mode is more convenient in general than :bridged mode that we will
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



### Custom Hardware Models

TODO

### Custom Images

TODO

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
[sonatype]: http://oss.sonatype.org/content/repositories/releases/org/cloudhoist "Sonatype Maven Repository"

[vmfest]: https://github.com/tbatchelli/vmfest "vmfest"
[virtualbox]: http://virtualbox.org/ "VirtualBox"
