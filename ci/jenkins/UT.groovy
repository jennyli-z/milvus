#!/usr/bin/env groovy

int total_timeout_minutes = 60 * 1

pipeline {
    options {
        timestamps()
        timeout(time: total_timeout_minutes, unit: 'MINUTES')
        buildDiscarder logRotator(artifactDaysToKeepStr: '30')
        parallelsAlwaysFailFast()
        preserveStashes(buildCount: 5)
        disableConcurrentBuilds(abortPrevious: true)

    }
    agent {
            kubernetes {
                inheritFrom 'default'
                defaultContainer 'main'
                yamlFile 'ci/jenkins/pod/ut.yaml'
                customWorkspace '/home/jenkins/agent/workspace'
            }
    }
    environment {
        PROJECT_NAME = 'milvus'
    }

    stages {
        stage ('UT'){
            steps {
                container('main') {
                    dir ('build'){
                            sh './set_docker_mirror.sh'
                    }
                    sh 'docker-compose up -d pulsar etcd minio'
                    sh """
                    ./build/builder.sh /bin/bash -c "make ci-ut"
                    """
                }
            }
        }
    }
    post{
        unsuccessful {
                container('jnlp') {
                    dir ('tests/scripts') {
                        script {
                            sh 'sleep 48'
                            // def authorEmail = sh(returnStdout: true, script: './get_author_email.sh ')
                            emailext subject: '$DEFAULT_SUBJECT',
                            body: '$DEFAULT_CONTENT',
                            recipientProviders: [developers(), culprits()],
                            replyTo: '$DEFAULT_REPLYTO',
                            // to: "${authorEmail},devops@zilliz.com"
                            to: "jing.li@zilliz.com"
                        }
                    }
                }
            }
        }
}
