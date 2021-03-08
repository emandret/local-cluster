# Jenkins master-slave infrastructure on Kubernetes

## Introduction

Kubernetes is an open-source container-orchestration system for deploying and managing containers.

Kubernetes manages the underlying compute, network & storage infrastructure by providing core objects. Kubernetes provides four types of core objects, namely

* Pods
* Services
* Volumes
* Namespaces

A Pod is a group of one or more containers, with shared storage/network resources, and a specification for how to run the containers.

Apart from the core objects, Kubernetes also provides higher-level abstractions which allow developers to build, deploy and manage container-centric applications. Those abstractions are available through Kubernetes higher-level objects such as

* Deployments (which manage ReplicaSets)
* ReplicaSets
* StatefulSets
* DaemonSet

Kubernetes object model allows developer to write definition files in YAML as record of intent following the good practice that a declarative configuration is better than imperative commands.

## Quick setup

Run `vagrant up` and wait for several minutes. Go grab a coffee.

Copy `/home/vagrant/.kube` to manage the Kubernetes cluster from your local machine:

``` bash
scp -r vagrant@192.168.1.2:/home/vagrant/.kube $HOME
```

> Note: username and password are both `vagrant` .

## Storage for Jenkins

Jenkins stores all its state on disk, in the `JENKINS_HOME` directory. For example, `$JENKINS_HOME` is `/var/jenkins_home` in the official Jenkins Docker image. In order to preserve the configuration, build logs, and artifacts if the Jenkins master Pod dies, a PersistentVolume is required.

A `hostPath` volume mounts a file or directory from the host node's filesystem into your Pod. Unlike `emptyDir`, which is erased when a Pod is removed, the files or directories created are preserved after the Pod is deleted.

If you have a single-node cluster, you can use the following configuration to create a `hostPath` volume:

``` yaml
apiVersion: v1
kind: PersistentVolume
metadata:
  name: jenkins-pv-volume
  labels:
    type: local
spec:
  storageClassName: manual
  capacity:
    storage: 8Gi
  accessModes:
    - ReadWriteOnce
  persistentVolumeReclaimPolicy: Retain
  hostPath:
    path: "/var/jenkins_home"
    type: DirectoryOrCreate
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: jenkins-pvc-volume
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 4Gi
  storageClassName: manual
```

> Note: the files or directories created on the underlying node are only writable by root. You either need to run your process as root in a privileged Container or modify the file permissions on the host to be able to write to a `hostPath` volume

While this works very well on a single-node cluster, this setup will fail for a multi-node cluster because the files or directories created are not synced across nodes. In a production cluster, the administrator would provision a network resource, such as an NFS share, an AzureFile share or an Amazon EBS volume.

To avoid this issue, we use the new `local` PersistentVolume type which remembers which node was used to provision the volume. A `local` volume represents a mounted local storage device such as a disk, partition or directory. They also have a special annotation that makes any Pod that uses the volume to be scheduled on the same node where the local volume is mounted.

Because the Jenkins master Pod is running under UID 1000 and GID 1000, we must first create the PersistentVolume storage directory and give it the correct ownership and permissions with the following commands:

``` bash
sudo mkdir -p /var/jenkins_home
sudo chown -R 1000:1000 /var/jenkins_home
sudo chmod -R 755 /var/jenkins_home
```

Thus, because we mount a directory from the node to a container running in a Pod, the `local` PV behaves like a bind mount in Linux and Docker.

> Note: these commands should be executed inside the node corresponding to the hostname specified under `nodeAffinity` in the configuration file, in this setup it should be `kubemaster` and you can run `vagrant ssh kubemaster` to open a shell inside the node.

## Kubernetes plugin for Jenkins

The connection with the Kubernetes cluster is made possible thanks to the Kubernetes plugin. In the section below we will install and configure the plugin accordingly.

First go to the Jenkins dashboard by choosing an IP belonging to a node inside the cluster and the associated port as specified under `nodePort` in the `jenkins-http` service. Using the Vagrant configuration, the dashboard is accessible at http://192.168.1.2:31000.

> Note: the NodePort service `jenkins-http` configures the `kube-proxy` in every node to listen on all interfaces and port 31000
>
> You can list all the nodes and their IPs with `kubectl get nodes -o wide` and you can list services with `kubectl get services -o wide -n jenkins` to see the bound ports.

Install the Kubernetes plugin in `Manage Jenkins > Manage Plugins > Available > [search for Kubernetes] > Download now and install after restart` and wait for the Jenkins master to restart.

Add a new a Kubernetes credential by going to `Manage Jenkins > Manage Credentials` and under `Stores scoped to Jenkins` select `(global) > Add credentials` and fill in the following values:

Field  | Value
-------|-----------------------------------------------------------------------------------------------------------------------------------------------------------
Kind   | Secret text
Scope  | System (Jenkins and nodes only)
Secret | `kubectl get secret $(kubectl get sa jenkins-master -n jenkins -o jsonpath='{.secrets[0].name}') -n jenkins -o jsonpath='{.data.token}' | base64 --decode`
ID     | [intentionally left blank]

Then go to `Manage Jenkins > Manage Nodes and Clouds > Configure Clouds > Add a new cloud > Kubernetes > [choose a name]` and click on `Kubernetes Cloud details...` and fill in the following values:

Field                             | Value
----------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------
Kubernetes URL                    | `kubectl config view -o jsonpath='{.clusters[0].cluster.server}'`
Kubernetes server certificate key | `kubectl get secret $(kubectl get sa jenkins-master -n jenkins -o jsonpath='{.secrets[0].name}') -n jenkins -o jsonpath='{.data.ca\.crt}' | base64 --decode`
Credentials                       | [choose the credential configured earlier]
Kubernetes Namespace              | `jenkins`
Jenkins URL                       | `http://jenkins-http:8080`
Jenkins tunnel                    | `jenkins-jnlp:50000`

> Note: the ClusterIP service `jenkins-jnlp` configures iptables rules which capture incoming traffic to the clusterIP and port 50000 and forward each request to TCP port 50000 on any Pod with the `app=jenkins-master` label.
>
> The rules can be listed with `iptables -t nat -n -L` and looking for `jenkins-jnlp` in the comments.

Finally click on `Pod Templates...` to create a new Pod template and click on `Pod Template details...` and fill in the following values:

Field     | Value
----------|----------------------------------------------------------
Name      | `jenkins-slave`
Namespace | `jenkins`
Labels    | `jenkins-slave`
Usage     | Only build jobs with label expressions matching this node

> Note: to use this slave Pod as an agent, we need to add: `agent { label ` jenkins-slave ` }` at the beginning of the pipeline definition.

Under `Containers` edit the `Container Template` with the following values:

Field        | Value
-------------|--------------------------
Name         | `ansible-runner`
Docker image | `emandret/ansible-alpine`

> Note: other values should be left as they are.

Eventually, click on `Save` and `Apply` to finish the setup. The whole configuration should be stored as a PersistentVolume so that it does not have to be recreated each time the Jenkins master Pod is terminated.

## Scheduling

Jenkins will schedule build processes on the Jenkins master by default, to prevent this, we can explicitly set the number of executor processes to zero.

> The number of executor processes is configured in `Manage Jenkins > Manage Nodes and Clouds > [select a node] > Configure > # of executors`

When we trigger a job from a pipeline, the Jenkins master Pod will look for an agent to execute the job. The agent can be a physical machine, but in our case, the Jenkins master Pod will spawn an agent slave Pod.

The Pod template section is used to configure the slave Pod. By default, the template defines a single container named `jnlp` using the image `jenkins/agent` to establish an inbound connection to the Jenkins master using TCP or WebSockets.

> Note: the Jenkins master Pod interacts directly with the Kubernetes API to execute commands inside containers, it does not use the agent to execute anything, see this [source code](https://github.com/jenkinsci/kubernetes-plugin/blob/master/src/main/java/org/csanchez/jenkins/plugins/kubernetes/pipeline/ContainerExecDecorator.java).

It is not recommended to override the `jnlp` container, instead, we should add our own container definition to the template: we added a containezr named `ansible-runner` in the previous section.

The Jenkins master Pod needs to schedule the executor processes inside the containers so they should not instantly die after having been created. For that, the container will idle with `sleep 9999` while waiting for a task.

> Note: do not override the default command, if the container cannot idle it will die before being able to execute any task.

Nodes can be defined in a pipeline and then used, however, default execution always goes to the `jnlp` container. You will need to specify the name of the container you want to execute your task in.

This will run in the `jnlp` container:

``` groovy
pipeline {
  agent {
    label 'jenkins-slave'
  }
  stages {
    stage('Test') {
      steps {
        sh "echo hello from $POD_CONTAINER"
      }
    }
  }
}
```

While this will run in the `ansible-runner` container:

``` groovy
pipeline {
  agent {
    label 'jenkins-slave'
  }`
  stages {
    stage('Test') {
      steps {
        container('ansible-runner') {
          sh "echo hello from $POD_CONTAINER"
        }
      }
    }
  }
}
```

## Sharing folders

In order to access our playbooks, we need to mount a folder inside the `ansible-runner` container. The current setup with Vagrant mounts an NFS folder from my local machine on each `worker-*` VM at `/nfs/shared`.

We are going to use an `hostPath` volume to mount the `/nfs/shared` folder inside the container. The `hostPath` volume can be referenced through a `persistentVolumeClaim` or configured directly:

```yaml
volumes:
  - hostPath:
    path: "/nfs/shared"
    name: "ansible-hostpath"
```

Then, within the `container` section of your Pod definition, add the following:

```yaml
volumeMounts:
  - mountPath: "/var/playbooks"
    name: "ansible-hostpath"
    readOnly: false
```

Great, now you can access your playbooks at `/var/playbooks` in your pipeline.

## Updates

To perform an update of the Jenkins image, you can run the following command:

``` bash
kubectl rollout restart deployment/jenkins-master -n jenkins
```

Since the tag used is `latest` and `imagePullPolicy` is set to `Always` the image will be automatically pulled from the Docker hub. If you need a specific tag, you can run the following command:

``` bash
kubectl set image deployment/jenkins-master jenkins-master=jenkins/jenkins:2.278 -n jenkins
```

The command notified the Deployment to use a different image for the Jenkins master Pod and initiated a rolling update.

## Troubleshooting

If for some reasons you face issues with Kubernetes, run the following command to reset the cluster:

``` bash
ansible-playbook -i .vagrant/provisioners/ansible/inventory/vagrant_ansible_inventory kubeadm-reset.yml
```

> Note: replace `.vagrant/provisioners/ansible/inventory/vagrant_ansible_inventory` (default for Vagrant provisioner) with your inventory file.

## Sources

https://devopscube.com/docker-containers-as-build-slaves-jenkins
https://www.jenkins.io/doc/book/installing/kubernetes/#install-jenkins-with-yaml-files
https://www.jenkins.io/doc/book/installing/kubernetes/#create-a-persistent-volume
https://kubernetes.io/docs/concepts/storage/persistent-volumes/#types-of-persistent-volumes
https://kubernetes.io/docs/concepts/configuration/overview/#container-images
