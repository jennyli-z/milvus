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
        parallelsAlwaysFailFast()
        preserveStashes(buildCount: 5)

    }
    agent {
            kubernetes {
                label 'milvus-e2e-test-opreator'
                inheritFrom 'default'
                defaultContainer 'main'
                yamlFile 'build/ci/jenkins/pod/rte.yaml'
                customWorkspace '/home/jenkins/agent/workspace'
            }
    }
    environment {
        PROJECT_NAME = 'milvus'
        SEMVER = "${BRANCH_NAME.contains('/') ? BRANCH_NAME.substring(BRANCH_NAME.lastIndexOf('/') + 1) : BRANCH_NAME}"
        DOCKER_BUILDKIT = 1
        ARTIFACTS = "${env.WORKSPACE}/_artifacts"
        DOCKER_CREDENTIALS_ID = "f0aacc8e-33f2-458a-ba9e-2c44f431b4d2"
        TARGET_REPO = "milvusdb"
        CI_DOCKER_CREDENTIAL_ID = "ci-docker-registry"
        MILVUS_HELM_NAMESPACE = "operator-ci"
        DISABLE_KIND = true
        HUB = 'registry.milvus.io/milvus'
        JENKINS_BUILD_ID = "${env.BUILD_ID}"
    }

    stages {
        stage('Install & E2E Test') {
            matrix {
                axes {
                    axis {
                        name 'MILVUS_SERVER_TYPE'
                        values 'standalone', 'distributed'
                    }
                }
                stages {
                    stage('Install') {
                        steps {
                            container('main') {
                                dir ('tests/scripts/operator') {
                                    script {
                                        sh 'printenv'
                                        def clusterEnabled = "false"
                                        if ("${MILVUS_SERVER_TYPE}" == 'distributed') {
                                            clusterEnabled = "true"
                                        }
                                        // withCredentials([usernamePassword(credentialsId: "${env.CI_DOCKER_CREDENTIAL_ID}", usernameVariable: 'CI_REGISTRY_USERNAME', passwordVariable: 'CI_REGISTRY_PASSWORD')]){
                                        //     sh """
                                        //     MILVUS_CLUSTER_ENABLED=${clusterEnabled} \
                                        //     TAG=${imageTag}\
                                        //     ./install_milvus.sh
                                        //     """
                                        // }
                                    }
                                }
                            }
                    }
                    stage('E2E Test'){
                        steps {
                            container('pytest') {
                                dir ('tests/scripts') {
                                    script {
                                        def release_name=sh(returnStdout: true, script: './get_release_name.sh')
                                        def clusterEnabled = 'false'
                                        if ("${MILVUS_SERVER_TYPE}" == "distributed") {
                                            clusterEnabled = "true"
                                        }
                                        if ("${MILVUS_CLIENT}" == "pymilvus") {
                                            sh """
                                            MILVUS_HELM_RELEASE_NAME="${release_name}" \
                                            MILVUS_HELM_NAMESPACE="operator-ci" \
                                            MILVUS_CLUSTER_ENABLED="${clusterEnabled}" \
                                            TEST_TIMEOUT="${e2e_timeout_seconds}" \
                                            ./ci_e2e.sh  "-n 6 -x --tags L0 L1 --timeout ${case_timeout_seconds}"
                                            """
                                        } else {
                                        error "Error: Unsupported Milvus client: ${MILVUS_CLIENT}"
                                        }
                                    }
                                }
                            }
                        }

                    }
                }
                post{
                    always {
                        container('main') {
                            dir ('tests/scripts/operator') {  
                                script {
                                    def release_name=sh(returnStdout: true, script: './get_release_name.sh')
                                    sh "./uninstall_milvus.sh --release-name ${release_name}"
                                }
                            }
                        }
                        container('pytest') {
                            dir ('tests/scripts') {
                                script {
                                        def release_name = sh(returnStdout: true, script: './get_release_name.sh ')
                                        sh "./ci_logs.sh --log-dir /ci-logs  --artifacts-name ${env.ARTIFACTS}/artifacts-${PROJECT_NAME}-${MILVUS_SERVER_TYPE}-${SEMVER}-${env.BUILD_NUMBER}-${MILVUS_CLIENT}-e2e-logs \
                                        --release-name ${release_name}"
                                        dir("${env.ARTIFACTS}") {
                                            if ("${MILVUS_CLIENT}" == "pymilvus") {
                                                sh "tar -zcvf artifacts-${PROJECT_NAME}-${MILVUS_SERVER_TYPE}-${MILVUS_CLIENT}-pytest-logs.tar.gz /tmp/ci_logs/test --remove-files || true"
                                                }
                                            archiveArtifacts artifacts: "artifacts-${PROJECT_NAME}-${MILVUS_SERVER_TYPE}-${MILVUS_CLIENT}-pytest-logs.tar.gz ", allowEmptyArchive: true
                                            archiveArtifacts artifacts: "artifacts-${PROJECT_NAME}-${MILVUS_SERVER_TYPE}-${SEMVER}-${env.BUILD_NUMBER}-${MILVUS_CLIENT}-e2e-logs.tar.gz", allowEmptyArchive: true
                                        }
                                }
                            }
                        }
                    }
                }
            }

        }
    }
    post{
        unsuccessful {
                container('jnlp') {
                    dir ('tests/scripts') {
                        script {
                            def authorEmail = sh(returnStdout: true, script: './get_author_email.sh ')
                            emailext subject: '$DEFAULT_SUBJECT',
                            body: '$DEFAULT_CONTENT',
                            recipientProviders: [developers(), culprits()],
                            replyTo: '$DEFAULT_REPLYTO',
                            to: "jing.li@zilliz.com"
                        }
                    }
                }
            }
        }
}