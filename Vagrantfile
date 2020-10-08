# -*- mode: ruby -*-
# vi: set ft=ruby :

Vagrant.configure("2") do |config|

  # modified ubuntu/bionic64
  config.vm.box = "hashicorp/bionic64"

  # kubemaster node
  config.vm.define "kubemaster" do |kubemaster|
    kubemaster.vm.hostname = "kubemaster"
    kubemaster.vm.network "private_network", ip: "192.168.1.2"
    kubemaster.vm.provider "virtualbox" do |v|
      v.memory = 2048
      v.cpus = 2
    end
    # note: the default inventory file is located at .vagrant/provisioners/ansible/inventory/vagrant_ansible_inventory
    kubemaster.vm.provision "ansible" do |ansible|
      ansible.playbook = "master-playbook.yml"
      ansible.extra_vars = {
        node_ip: "192.168.1.2",
        node_name: "kubemaster"
      }
    end
  end

  # workers
  (1..3).each do |i|
    config.vm.define "worker-#{i}" do |worker|
      worker.vm.hostname = "worker-#{i}"
      worker.vm.network "private_network", ip: "192.168.1.#{i + 2}"
      worker.vm.provider "virtualbox" do |v|
        v.memory = 1024
        v.cpus = 1
      end
      worker.vm.provision "ansible" do |ansible|
        ansible.playbook = "worker-playbook.yml"
        ansible.extra_vars = {
        node_ip: "192.168.1.#{i + 2}",
        node_name: "worker-#{i}"
        }
      end
    end
  end

end
