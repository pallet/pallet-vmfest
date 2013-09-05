# pallet-vmfest

```pallet-vmfest``` lets you use [Pallet][palletops] to manage [virtualbox][virtualbox] vm's just like you would any other cloud provider.  You can test your configuration and crates locally and, behind-the-scenes, the [vmfest][vmfest] library handles driving VirtualBox for you.

You can learn more about how to use Pallet from the online [documentation][docs].


## Prerequisites

1. A Pallet clojure project

  The simplest way to create one is to install [leiningen][leiningen] and then run the following command:
  ```bash 
  $ lein new pallet quickstart
  ```

2. [VirtualBox 4.2.x](https://www.virtualbox.org/wiki/Downloads)
 (latest). It won't work with older versions of VirtualBox.


## Usage

### Step 1. Update classpath

```pallet-vmfest``` is distributed as a jar, and is available in the
[clojars repository][sonatype].

If you use leiningen, add the following dependencies to your project.clj file (pallet will already be there if you used the leiningen pallet template):

``` clojure
:dependencies [[com.palletops/pallet "0.8.0-beta.9"]
               [com.palletops/pallet-vmfest "0.3.0-alpha.5"]]
```

If you use maven, add the following to your pom.xml file:

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
    <version>0.3.0-alpha.5</version>
  </dependency>
<dependencies>
```

### Step 2. Setup communication with VirtualBox

#### OSX

```pallet-vmfest``` can use XPCOM to transparently communicate with VirtualBox on OSX.  (If your Linux distro supports XPCOM then this method will also work for you)

1. Open a clojure repl:
  ```bash 
  $ cd quickstart
  $ lein deps
  $ lein repl
  ```

2. Configure pallet to use the "vmfest" cloud provider

  ``` clojure
  (require '[pallet.compute :refer [instantiate-provider]])
  (def vmfest (instantiate-provider "vmfest"))
  
  ```

  or, add this to your `~/.pallet/config.clj` file:

  ``` clojure
  (defpallet :services {:vmfest {:provider "vmfest"}})
  ``` 



#### Windows, Linux

```pallet-vmfest``` can always use web services to speak with VirtualBox, no matter the operating system.

1. Turn off auth (only needs to be done once)
  ```bash
  $ VBoxManage setproperty websrvauthlibrary null
  ```

2. Start VirtualBox listening
  ```shell
  $ vboxwebsrv -t0
  ```

3. Open a clojure repl:
  ```bash 
  $ cd quickstart
  $ lein deps
  $ lein repl
  ```

4. Configure pallet to use the "vmfest" cloud provider
  ```clojure
  (require '[pallet.compute :refer [instantiate-provider]])
  (def vmfest (instantiate-provider "vmfest" 
                                      :vbox-comm :ws))
  ```

  or, add this to your `~/.pallet/config.clj` file:

  ``` clojure
  (defpallet :services {:vmfest {:provider "vmfest"
                                   :vbox-comm :ws}})
  ```


### Step 3. Install a vmfest model

The vmfest model consists of two parts:

  1. a virtualbox disk image (typically with a *.vdi extension) 
  2. a meta-data file with information about the image (*.meta extension)

Pre-made virtualbox images are available here: https://s3.amazonaws.com/vmfest-images/

#### Option A - Let pallet-vmfest download an image for you

  1. From a repl,

  ```clojure
  (require '[pallet.compute.vmfest :refer [add-image]])
  (add-image vmfest
               "https://s3.amazonaws.com/vmfest-images/ubuntu-13.04-64bit.vdi.gz")
  ```

#### Option B - Download a vmfest virtualbox image yourself

  Configuring a virtualbox image to work with vmfest can be a bit complex due to the specifics of Guest Additions and network interface configuration so this guide assumes you are using one of the vmfest images we provide.  You can explore the core [vmfest][vmfest] project for more info on creating your own image.

  1. Download one of our existing vmfest images + draft meta file

    for example,
    ```bash 
    $ cd ~/Downloads
    $ wget https://s3.amazonaws.com/vmfest-images/ubuntu-13.04-64bit.meta
    $ wget https://s3.amazonaws.com/vmfest-images/ubuntu-13.04-64bit.vdi.gz
    ```

  2. Install the model from a repl:
  ```clojure
  (require '[pallet.compute.vmfest :refer [add-image]])
  (add-image vmfest
               "/Users/alanning/Downloads/ubuntu-13.04-64bit.vdi.gz")
  ```

  ```Note:``` File path must be absolute. 

  ```Note:``` ~/.vmfest/models will contain the installed model (image + meta-data file).  You can remove the original files if you like.
    

### Step 4. Verify image has been installed

  1. From a repl, 

  ```clojure
  (require '[pallet.compute :refer [images]]
             '[clojure.pprint :refer [pprint]])
  (pprint (images vmfest))
  ```

  ```
  => {:ubuntu-13.04-64bit
       {:os-type-id "Ubuntu_64",
        :sudo-password "vmfest",
        :no-sudo false,
        :image-name "ubuntu-13.04-64bit",
        :packager :apt,
        :username "vmfest",
        :os-family :ubuntu,
        :os-version "13.04",
        :uuid "/Users/alanning/.vmfest/models/vmfest-ubuntu-13.04-64bit.vdi",
        :os-64-bit true,
        :image-id "ubuntu-13.04-64bit",
        :password "vmfest",
        :description "Ubuntu 13.04 (64bit)"}}
  ```


### Step 5. Spin up an instance

Now that the model has been installed, we can use it when defining our pallet [group-spec][group-spec] (a configuration definition used by pallet when starting instances).

  1. Define a group-spec

  You can reference models directly...

  ```clojure
  (require '[pallet.api :refer [group-spec]])
  (def ubuntu-group 
      (group-spec "ubuntu-vms" 
                  :node-spec {:image {:image-id :ubuntu-13.04}}))
  ```

  or just specify an appropriate template (just like with any other cloud provider) ...

  ```clojure
  (require '[pallet.api :refer [group-spec]])
  (def ubuntu-group 
      (group-spec "ubuntu-vms" 
                  :node-spec {:image {:image {:os-family :ubuntu      
                                              :os-64-bit? true }}}))
  ```

  2. Spin up an instance

  ```clojure
  (require '[pallet.api :refer [converge]])
  (pallet.api/converge {ubuntu-group 1}
                         :compute vmfest)
  ```

  3. Get ip address
  ```clojure
  (require '[pallet.compute :refer [nodes]])
  (nodes vmfest)
  ```
  ```
  => (ubuntu-vms-0  ubuntu-vms  public: 192.168.56.101)
  ```

  4. SSH into box (using credentials from .meta file)
  ```bash
  $ ssh vmfest@192.168.56.101
  ```

  5. When you are ready, shut down the instance

  ```clojure
  (require '[pallet.api :refer [converge]])
  (converge {ubuntu-group 0}
              :compute vmfest)
  ```


  Done!


## Next Steps

Where to go from here:

 * An example of [deploying a webapp][example-deploy-webapp] using Pallet
 * The [Pallet github][pallet-github] site for a list of pre-made crates
 * The [Pallet reference docs][pallet-reference-docs] for more details on Pallet concepts


## Further configuration

### Networking modes

```pallet-vmfest``` can work with 2 different network models

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


## Troubleshooting

### ```instantiate-provider``` fails

If the call to ```pallet.compute/instantiate-provider``` fails, it probably means you haven't installed the model using ```add-image```.  One way this could occur is if you just place the draft .meta file template provided by vmfest into your ```~/.vmfest/models``` directory.  The draft .meta file is incomplete; the ```add-image``` function will flesh it out for you and place it in the proper location.

### Obtaining a stack trace

In a clojure REPL, ```*e``` contains the last exception.  So to see a stack trace you can either:

  a. Use the underlying Java function:
  ```clojure
  (.printStackTrace *e)
  ```

  b. Use Clojure's stack trace api:
  ```clojure
  (use 'clojure.stacktrace)
  (print-stack-trace *e)
  ```


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
[leiningen]: http://github.com/technomancy/leiningen "Leiningen"
[group-spec]: http://palletops.com/doc/reference/0.8/node-types/ "group-spec documentation"
[example-deploy-webapp]: https://github.com/pallet/example-deploy-webapp "deploying a webapp"
[pallet-github]: https://github.com/pallet "Pallet github"
[pallet-reference-docs]: http://palletops.com/doc/reference-0.8/ "Pallet reference docs"
