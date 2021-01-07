#!/bin/bash

# reset kubeadm
kubeadm reset

# delete kubeconfig
rm -rf /home/vagrant/.kube
rm -rf /etc/cni/net.d

tables=(
  filter
  nat
  mangle
  raw
  security
)

# flush and delete all iptables rules in all tables
for t in "${tables[@]}"; do
  iptables -t $t -F
  iptables -t $t -X
done
