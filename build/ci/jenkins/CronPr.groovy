#!/usr/bin/env groovy

int total_timeout_minutes = 180
int e2e_timeout_seconds = 120 * 60
// def imageTag=''
int case_timeout_seconds = 10 * 60
String cron_timezone = 'TZ=Asia/Shanghai'
String cron_string =  "H */24 * * * " 
def chart_version='2.5.0'
pipeline {
    triggers {
        cron """${cron_timezone}
            ${cron_string}"""
    }
    options {
        timestamps()
        timeout(time: total_timeout_minutes, unit: 'MINUTES')
        buildDiscarder logRotator(artifactDaysToKeepStr: '30')
        // parallelsAlwaysFailFast()
        preserveStashes(buildCount: 5)

    }
    parameters{
        string(
            description: 'Image Tag',
            name: 'image_tag',
            defaultValue: 'v2.0.0'
        ) 
        string(
            description: 'Fail & stop',
            name: 'stop',
            defaultValue: '-x'
        ) 
        string(
            description: 'Test Level',
            name: 'test_level',
            defaultValue: 'L0 L1 L2'
        ) 
    }
    agent {
            kubernetes {
                label 'milvus-e2e-test-pipeline'
                inheritFrom 'default'
                defaultContainer 'main'
                yamlFile 'build/ci/jenkins/pod/rte.yaml'
                customWorkspace '/home/jenkins/agent/workspace'
            }
    }
    environment {
        PROJECT_NAME = 'milvus'
        DOCKER_BUILDKIT = 1
        ARTIFACTS = "${env.WORKSPACE}/_artifacts"
        DOCKER_CREDENTIALS_ID = "f0aacc8e-33f2-458a-ba9e-2c44f431b4d2"
        TARGET_REPO = "milvusdb"
        // CI_DOCKER_CREDENTIAL_ID = "ci-docker-registry"
        MILVUS_HELM_NAMESPACE = "chaos-testing"
        DISABLE_KIND = true
        HUB = 'harbor.zilliz.cc/milvus'
        JENKINS_BUILD_ID = "${env.BUILD_ID}"
        CI_MODE="pr"
    }

    stages {
        // stage ('Build'){
        //     steps {
        //         container('main') {
        //             dir ('build'){
        //                     sh './set_docker_mirror.sh'
        //             }
        //             dir ('tests/scripts') {
        //                 script {
        //                     sh 'printenv'
        //                     def date = sh(returnStdout: true, script: 'date +%Y%m%d').trim()
        //                     def gitShortCommit = sh(returnStdout: true, script: 'git rev-parse --short HEAD').trim()    
        //                     imageTag="${env.BRANCH_NAME}-${date}-${gitShortCommit}"
        //                     withCredentials([usernamePassword(credentialsId: "${env.CI_DOCKER_CREDENTIAL_ID}", usernameVariable: 'CI_REGISTRY_USERNAME', passwordVariable: 'CI_REGISTRY_PASSWORD')]){
        //                         sh """
        //                         TAG="${imageTag}" \
        //                         ./e2e-k8s.sh \
        //                         --skip-export-logs \
        //                         --skip-install \
        //                         --skip-cleanup \
        //                         --skip-setup \
        //                         --skip-test
        //                         """

        //                         // stash imageTag info for rebuild install & E2E Test only
        //                         sh "echo ${imageTag} > imageTag.txt"
        //                         stash includes: 'imageTag.txt', name: 'imageTag'

        //                     }
        //                 }
        //             }
        //         }
        //     }
        // }


        stage('Install & E2E Test') {
            matrix {
                axes {
                    axis {
                        name 'MILVUS_SERVER_TYPE'
                        values 'standalone', 'distributed'
                    }
                    axis {
                        name 'MILVUS_CLIENT'
                        values 'pymilvus'
                    }
                }

                stages {
                    stage('Install') {
                        steps {
                            container('main') {
                                dir ('tests/scripts') {
                                    script {
                                        // sh 'printenv'
                                        def clusterEnabled = "false"
                                        if ("${MILVUS_SERVER_TYPE}" == 'distributed') {
                                            clusterEnabled = "true"
                                        }

                                        if ("${MILVUS_CLIENT}" == "pymilvus") {
                                            // imageTag=""
                                            // if ("${imageTag}"==''){
                                            //     dir ("imageTag"){
                                            //         try{
                                            //             unstash 'imageTag'
                                            //             imageTag=sh(returnStdout: true, script: 'cat imageTag.txt | tr -d \'\n\r\'')
                                            //         }catch(e){
                                            //             print "No Image Tag info remained ,please rerun build to build new image."
                                            //             exit 1
                                            //         }
                                            //     }
                                            // }
                                            // withCredentials([usernamePassword(credentialsId: "${env.CI_DOCKER_CREDENTIAL_ID}", usernameVariable: 'CI_REGISTRY_USERNAME', passwordVariable: 'CI_REGISTRY_PASSWORD')]){
                                                sh """
                                                MILVUS_CLUSTER_ENABLED=${clusterEnabled} \
                                                TAG=${image_tag}\
                                                ./e2e-k8s.sh \
                                                --skip-export-logs \
                                                --skip-cleanup \
                                                --skip-setup \
                                                --skip-test \
                                                --skip-build \
                                                --skip-build-image \
                                                --install-extra-arg "--set etcd.persistence.storageClass=local-path \
                                                --set minio.persistence.storageClass=local-path \
                                                --set etcd.metrics.enabled=false \
                                                --set etcd.metrics.podMonitor.enabled=false \
                
                                                --set metrics.serviceMonitor.enabled=true \
                                                 --version ${chart_version} \
                                                -f values/pr.yaml" 
                                                """
                                            // }
                                        } else {
                                            error "Error: Unsupported Milvus client: ${MILVUS_CLIENT}"
                                        }
                                    }
                                }
                            }
                        }
                    }
                    stage('E2E Test'){
                        agent {
                                kubernetes {
                                    label 'milvus-e2e-test-pr'
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
                                        def release_name=sh(returnStdout: true, script: './get_release_name.sh')
                                        def clusterEnabled = 'false'
                                        if ("${MILVUS_SERVER_TYPE}" == "distributed") {
                                            clusterEnabled = "true"
                                        }
                                        if ("${MILVUS_CLIENT}" == "pymilvus") {
                                            sh """
                                            MILVUS_HELM_RELEASE_NAME="${release_name}" \
                                            MILVUS_HELM_NAMESPACE="chaos-testing" \
                                            MILVUS_CLUSTER_ENABLED="${clusterEnabled}" \
                                            TEST_TIMEOUT="${e2e_timeout_seconds}" \
                                            ./ci_e2e.sh  "-n 6 ${stop} --tags ${test_level} --timeout ${case_timeout_seconds}"
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
                        container('pytest'){
                            dir("${env.ARTIFACTS}") {
                                    sh "tar -zcvf artifacts-${PROJECT_NAME}-${MILVUS_SERVER_TYPE}-${MILVUS_CLIENT}-pytest-logs.tar.gz /tmp/ci_logs/test --remove-files || true"
                                    archiveArtifacts artifacts: "artifacts-${PROJECT_NAME}-${MILVUS_SERVER_TYPE}-${MILVUS_CLIENT}-pytest-logs.tar.gz ", allowEmptyArchive: true
                            }
                        }
                        container('main') {
                            dir ('tests/scripts') {  
                                script {
                                    def release_name=sh(returnStdout: true, script: './get_release_name.sh')
                                    sh "./uninstall_milvus.sh --release-name ${release_name}"
                                    sh "./ci_logs.sh --log-dir /ci-logs  --artifacts-name ${env.ARTIFACTS}/artifacts-${PROJECT_NAME}-${MILVUS_SERVER_TYPE}-${env.BUILD_NUMBER}-${MILVUS_CLIENT}-e2e-logs \
                                    --release-name ${release_name}"
                                    dir("${env.ARTIFACTS}") {
                                        archiveArtifacts artifacts: "artifacts-${PROJECT_NAME}-${MILVUS_SERVER_TYPE}-${env.BUILD_NUMBER}-${MILVUS_CLIENT}-e2e-logs.tar.gz", allowEmptyArchive: true
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
        always {
                container('jnlp') {
                    dir ('tests/scripts') {
                        script {
                            emailext subject: 'Test for Pulsar 2.8.2 $DEFAULT_SUBJECT',
                            body: '$DEFAULT_CONTENT',
                            recipientProviders: [developers(), culprits()],
                            replyTo: '$DEFAULT_REPLYTO',
                            to: "jing.li@zilliz.com,yanliang.qiao@zilliz.com,jie.zeng@zilliz.com"
                        }
                    }
                }
            }
        }
}