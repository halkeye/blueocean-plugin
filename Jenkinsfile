#!groovy

// only 20 builds
properties([buildDiscarder(logRotator(artifactNumToKeepStr: '20', numToKeepStr: '20'))])

node {

  deleteDir()
  checkout scm
  sh 'docker build -t blueocean_build_env - < Dockerfile.build'

  configFileProvider([configFile(fileId: 'blueocean-maven-settings', targetLocation: 'settings.xml')]) {
    docker.image('blueocean_build_env').inside {
      withEnv(['GIT_COMMITTER_EMAIL=me@hatescake.com','GIT_COMMITTER_NAME=Hates','GIT_AUTHOR_NAME=Cake','GIT_AUTHOR_EMAIL=hates@cake.com']) {
        try {
          sh 'npm --prefix ./blueocean-core-js install'
          sh 'npm --prefix ./blueocean-core-js run gulp'
          sh "mvn clean install -B -DcleanNode -Dmaven.test.failure.ignore -s settings.xml -Dmaven.artifact.threads=30"
          sh "node ./bin/checkdeps.js"
          sh "node ./bin/checkshrinkwrap.js"
          step([$class: 'JUnitResultArchiver', testResults: '**/target/surefire-reports/TEST-*.xml'])
          step([$class: 'ArtifactArchiver', artifacts: '*/target/*.hpi'])
        } catch(err) {
          currentBuild.result = "FAILURE"
        } finally {
          deleteDir()
        }
      }
    }
  }
}
