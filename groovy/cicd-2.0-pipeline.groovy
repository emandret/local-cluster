/* groovylint-disable-next-line CompileStatic */
pipeline {
  agent {
    label 'jenkins-slave'
  }
  parameters {
    string(name: 'CLIENT', defaultValue: '', description: 'Choose the client name')
    choice(name: 'CLOUD', choices: ['azure', 'aws'], description: 'Choose the cloud provider')
  }
  stages {
    stage('Ansible') {
      steps {
        container('ansible-runner') {
          sh """
            cd /var/playbooks
            ansible-playbook \
              -i inventory.ini \
              -e "client=${params.CLIENT} cloud=${params.CLOUD}" \
              vm-deploy.yml
            cd -
          """
        }
      }
    }
  }
}
