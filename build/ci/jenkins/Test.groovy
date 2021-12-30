#!/usr/bin/env groovy

int total_timeout_minutes = 120
int e2e_timeout_seconds = 70 * 60
def imageTag=''
int case_timeout_seconds = 10 * 60
pipeline {
    options {
        timestamps()
        timeout(time: total_timeout_minutes, unit: 'MINUTES')
        buildDiscarder logRotator(artifactDaysToKeepStr: '30')
        // parallelsAlwaysFailFast()
        preserveStashes(buildCount: 5)

    }
    agent none
    environment {
        PROJECT_NAME = 'milvus'
        SEMVER = "${BRANCH_NAME.contains('/') ? BRANCH_NAME.substring(BRANCH_NAME.lastIndexOf('/') + 1) : BRANCH_NAME}"
        DOCKER_BUILDKIT = 1
        ARTIFACTS = "${env.WORKSPACE}/_artifacts"
        DOCKER_CREDENTIALS_ID = "f0aacc8e-33f2-458a-ba9e-2c44f431b4d2"
        TARGET_REPO = "milvusdb"
        CI_DOCKER_CREDENTIAL_ID = "ci-docker-registry"
        MILVUS_HELM_NAMESPACE = "milvus-ci"
        DISABLE_KIND = true
        HUB = 'registry.milvus.io/milvus'
        JENKINS_BUILD_ID = "${env.BUILD_ID}"
    }
stages{

        stage('E2E Test') {

            matrix {
                axes {
                    axis {
                        name 'RELEASE_NAME'
                        values 'master-new', 'master-old'
                    }
                }

                stages {
                    stage('E2E Test'){
                        agent {
                            kubernetes {
                                label 'pulsar-test'
                                inheritFrom 'default'
                                defaultContainer 'main'
                                yamlFile 'build/ci/jenkins/pod/rte.yaml'
                                customWorkspace '/home/jenkins/agent/workspace'
                            }
                       }
                        steps {
                            container('pytest') {
                                dir ('tests/scripts') {
                                    script {
                                        sh 'printenv'
                                        sh "echo ${RELEASE_NAME}"
                                        sh """
                                        MILVUS_HELM_RELEASE_NAME="${RELEASE_NAME}" \
                                        MILVUS_HELM_NAMESPACE="pulsar-test" \
                                        MILVUS_CLUSTER_ENABLED="true" \
                                        TEST_TIMEOUT="${e2e_timeout_seconds}" \
                                        ./ci_e2e.sh  "-n 6 -x --tags L0 L1 --timeout ${case_timeout_seconds}"
                                        """
                                    }
                                }
                            }
                        }

                    }
                }
            }
        }
}
}